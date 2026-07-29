package com.fxc.broker.pnl;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.md.MarketDataCache;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.Position;
import com.fxc.broker.model.Side;
import com.fxc.broker.oms.FillListener;
import com.fxc.common.instrument.AssetClass;
import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-account session profit and loss for the broker console (docs/DESIGN.md §6), plotted as
 * cumulative trade count against P&amp;L relative to the start of the session.
 *
 * <p><b>Definition.</b> Equity is valued in USD as cash plus share positions marked at the
 * exchange's last sale; {@code relative} is equity now minus equity when the session started.
 * {@code realized} is closed-trade P&amp;L (an equity sell against its volume-weighted cost basis) and
 * {@code unrealized} is the remainder, so the two always sum to {@code relative}. An FX spot fill
 * contributes nothing to {@code realized} — it exchanges one balance for another — and shows up
 * through revaluation of the resulting balances instead.
 *
 * <p><b>Why points are sampled on fills.</b> The broker does not historise marks, so a
 * mark-to-market curve cannot be reconstructed after the fact; each point is therefore computed at
 * the moment a fill is applied. Restarting the broker starts a new session, which is the correct
 * meaning of "over a trading session" for a demo component.
 *
 * <p><b>Approximations, stated rather than hidden.</b> A share position with no last sale yet is
 * marked at its cost basis — valuing a seeded holding at zero would invent an enormous gain on its
 * first trade. A holding whose currency has no resolvable USD rate is excluded and counted in
 * {@code unpricedHoldings} instead of being guessed at.
 *
 * <p><b>Known limitation.</b> The baseline is taken once and never revised. If an account holds a
 * balance that was unvaluable at session start and its rate appears later, that balance joins equity
 * without having been in the baseline, and the difference reads as profit. {@code unpricedHoldings}
 * is what lets a caller detect the situation. This is left as a stated limitation rather than
 * papered over with a silent mid-session re-baseline, which would reset the curve for reasons
 * invisible to whoever is reading it. It does not arise in the seeded demo, where accounts start with
 * USD cash and shares only.
 */
public final class PnlService implements FillListener {

    /** USD display scale. */
    private static final int SCALE = 2;
    /** Ceiling on retained points per account; reaching it is reported, never silent. */
    static final int MAX_POINTS = 20_000;

    /** An account's P&amp;L: live totals plus the fill-sampled curve the console plots. */
    public record AccountPnl(String account, String ownerName, BigDecimal baseline, BigDecimal equity,
                             BigDecimal realized, BigDecimal unrealized, BigDecimal relative,
                             int tradeCount, int unpricedHoldings, boolean truncated,
                             List<PnlPoint> points) {
    }

    private final AccountService accounts;
    private final MarketDataCache marketData;
    private final FxRates fx;
    private final LongSupplier clock;
    private final Map<String, Instrument> instruments = InstrumentCatalog.bySymbol();

    private final Map<String, BigDecimal> baselines = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> realized = new ConcurrentHashMap<>();
    private final Map<String, Integer> tradeCounts = new ConcurrentHashMap<>();
    private final Map<String, List<PnlPoint>> series = new ConcurrentHashMap<>();
    private final Map<String, Boolean> truncated = new ConcurrentHashMap<>();

    public PnlService(AccountService accounts, MarketDataCache marketData, LongSupplier clock) {
        this.accounts = accounts;
        this.marketData = marketData;
        this.fx = new FxRates(marketData);
        this.clock = clock;
    }

    /**
     * Record each account's starting equity. Call once after seeding: every later {@code relative}
     * is measured against this, so capturing it after trading has begun would understate the move.
     */
    public synchronized void captureBaselines() {
        for (BrokerRepository.AccountRow account : accounts.accounts()) {
            String number = account.accountNumber();
            Equity equity = equity(number);
            baselines.put(number, equity.total());
            realized.putIfAbsent(number, BigDecimal.ZERO);
            tradeCounts.putIfAbsent(number, 0);
            // Anchor the curve at zero so a one-fill account still draws a line rather than a dot.
            series.computeIfAbsent(number, k -> new ArrayList<>()).add(new PnlPoint(
                    0, clock.getAsLong(), scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO),
                    scaled(BigDecimal.ZERO)));
        }
    }

    @Override
    public synchronized void onFill(String account, Instrument instrument, Side side,
                                    BigDecimal lastQty, BigDecimal lastPx, long ts) {
        int count = tradeCounts.merge(account, 1, Integer::sum);

        if (instrument != null && instrument.assetClass() == AssetClass.EQUITY && side == Side.SELL) {
            // AccountService leaves avg_price untouched on sells, so the basis is still readable here
            // even though the fill has already been applied.
            BigDecimal basis = shareBasis(account, instrument.symbol());
            BigDecimal gain = lastPx.subtract(basis).multiply(lastQty);
            fx.convert(gain, instrument.quoteCurrency().getCurrencyCode())
                    .ifPresent(usd -> realized.merge(account, usd, BigDecimal::add));
        }

        Equity equity = equity(account);
        BigDecimal baseline = baselines.computeIfAbsent(account, k -> equity.total());
        BigDecimal relative = equity.total().subtract(baseline);
        BigDecimal realizedNow = realized.getOrDefault(account, BigDecimal.ZERO);

        List<PnlPoint> points = series.computeIfAbsent(account, k -> new ArrayList<>());
        if (points.size() >= MAX_POINTS) {
            if (truncated.putIfAbsent(account, Boolean.TRUE) == null) {
                System.err.println("P&L series for " + account + " reached " + MAX_POINTS
                        + " points; no longer appending (reported as truncated)");
            }
            return;
        }
        points.add(new PnlPoint(count, ts, scaled(realizedNow),
                scaled(relative.subtract(realizedNow)), scaled(relative)));
    }

    /** Every account's P&amp;L, with live totals and the curve so far. */
    public synchronized List<AccountPnl> series() {
        List<AccountPnl> out = new ArrayList<>();
        for (BrokerRepository.AccountRow account : accounts.accounts()) {
            String number = account.accountNumber();
            Equity equity = equity(number);
            BigDecimal baseline = baselines.getOrDefault(number, equity.total());
            BigDecimal relative = equity.total().subtract(baseline);
            BigDecimal realizedNow = realized.getOrDefault(number, BigDecimal.ZERO);
            List<PnlPoint> points = series.getOrDefault(number, List.of());
            out.add(new AccountPnl(number, account.ownerName(), scaled(baseline), scaled(equity.total()),
                    scaled(realizedNow), scaled(relative.subtract(realizedNow)), scaled(relative),
                    tradeCounts.getOrDefault(number, 0), equity.unpriced(),
                    Boolean.TRUE.equals(truncated.get(number)), List.copyOf(points)));
        }
        return out;
    }

    // --- valuation ---

    /** An equity valuation plus how many holdings could not be valued. */
    private record Equity(BigDecimal total, int unpriced) {
    }

    private Equity equity(String account) {
        BigDecimal total = BigDecimal.ZERO;
        int unpriced = 0;
        for (Position position : accounts.positions(account)) {
            if (position.quantity().signum() == 0) {
                continue;
            }
            Optional<BigDecimal> usd = position.holdingType() == HoldingType.CASH
                    ? fx.convert(position.quantity(), position.instrument())
                    : shareValueUsd(position);
            if (usd.isPresent()) {
                total = total.add(usd.get());
            } else {
                unpriced++;
            }
        }
        return new Equity(total, unpriced);
    }

    private Optional<BigDecimal> shareValueUsd(Position position) {
        Instrument instrument = instruments.get(position.instrument());
        if (instrument == null) {
            return Optional.empty(); // not a listed instrument; nothing to mark it against
        }
        Optional<BigDecimal> mark = marketData.lastPrice(position.instrument());
        BigDecimal price = mark.orElse(position.avgPrice());
        if (mark.isEmpty() && position.avgPrice().signum() == 0) {
            return Optional.empty(); // no mark and no cost basis — valuing it would be a guess
        }
        return fx.convert(position.quantity().multiply(price),
                instrument.quoteCurrency().getCurrencyCode());
    }

    private BigDecimal shareBasis(String account, String symbol) {
        for (Position position : accounts.positions(account)) {
            if (position.holdingType() == HoldingType.SHARE && position.instrument().equals(symbol)) {
                return position.avgPrice();
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
