package com.fxc.broker.pnl;

import com.fxc.broker.account.AccountOpenedListener;
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
 *
 * <p><b>The curve is a rolling window</b> (docs/stories/003). Points older than {@code windowMs} are
 * evicted, so a demo that runs for hours plots its last quarter of an hour rather than freezing at a
 * point ceiling — which is what the previous fixed cap did, while the payload it produced grew until
 * it did (docs/PROBLEMS.md P17). Three separate bounds, because they answer different questions:
 * {@code windowMs} decides what the chart <em>means</em>, {@code maxPointsPerAccount} decides what a
 * response costs, and {@code plotAccounts} decides how many accounts carry a curve at all. Eviction is
 * normal operation, not an error; {@code dropped} says how much has aged out, and totals are unaffected
 * because they are computed live rather than summed from the curve.
 */
public final class PnlService implements FillListener, AccountOpenedListener {

    /** USD display scale. */
    private static final int SCALE = 2;

    /** How much history the curve shows. 15 minutes: long enough to see a trend at ~5 fills/sec. */
    public static final long DEFAULT_WINDOW_MS = 15 * 60 * 1000L;

    /**
     * Points per account in a response. ~600 is the console chart's width in pixels, so downsampling
     * to it is invisible while bounding a payload that is fetched once a second.
     */
    public static final int DEFAULT_MAX_POINTS_PER_ACCOUNT = 600;

    /**
     * How many accounts each of the three groups carries (top by P&amp;L, most active, bottom by P&amp;L).
     * Five apiece plots at most fifteen curves — the palette has eight colours, so the console colours
     * by group rather than by account and says which group each line is in.
     */
    public static final int DEFAULT_GROUP_SIZE = 5;

    /**
     * Backstop on retained points per account, in case a fill rate puts more than this inside the
     * window. Still rolling — the oldest go first — so the curve never stops moving.
     */
    public static final int DEFAULT_MAX_RETAINED_POINTS = 5_000;

    /**
     * Accounts numbered below this are the broker's own — the issuer at 0 and the market makers at 1
     * and 2 — and never reach the console. Customer accounts are allocated from 100000 up.
     */
    public static final long DEFAULT_INTERNAL_ACCOUNTS_BELOW = 100;

    /** An account's P&amp;L: live totals plus the fill-sampled curve the console plots. */
    public record AccountPnl(String account, String ownerName, BigDecimal baseline, BigDecimal equity,
                             BigDecimal realized, BigDecimal unrealized, BigDecimal relative,
                             int tradeCount, int unpricedHoldings, long windowMs, int dropped,
                             boolean plotted, List<String> groups, List<PnlPoint> points) {
    }

    /**
     * Why an account is on the chart. With an account per agent there are far more accounts than
     * readable curves, and "the busiest" answers only one of the three questions an operator actually
     * asks — who is winning, who is losing, and who is doing the trading. So the selection is three
     * small groups rather than one long list, and an account can be in more than one (the most active
     * account is often also the worst).
     */
    public static final String GROUP_TOP = "top";
    public static final String GROUP_ACTIVE = "active";
    public static final String GROUP_BOTTOM = "bottom";

    private final AccountService accounts;
    private final MarketDataCache marketData;
    private final FxRates fx;
    private final LongSupplier clock;
    private final Map<String, Instrument> instruments = InstrumentCatalog.bySymbol();

    private final long windowMs;
    private final int maxPointsPerAccount;
    private final int groupSize;
    private final int maxRetainedPoints;
    private final long internalAccountsBelow;

    private final Map<String, BigDecimal> baselines = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> realized = new ConcurrentHashMap<>();
    private final Map<String, Integer> tradeCounts = new ConcurrentHashMap<>();
    private final Map<String, List<PnlPoint>> series = new ConcurrentHashMap<>();
    private final Map<String, Integer> dropped = new ConcurrentHashMap<>();

    public PnlService(AccountService accounts, MarketDataCache marketData, LongSupplier clock) {
        this(accounts, marketData, clock, PnlSettings.defaults());
    }

    public PnlService(AccountService accounts, MarketDataCache marketData, LongSupplier clock,
                      PnlSettings settings) {
        this.accounts = accounts;
        this.marketData = marketData;
        this.fx = new FxRates(marketData);
        this.clock = clock;
        // Clamped rather than validated: a nonsensical value in a conf file should not stop the broker
        // from starting, and every one of these has a sane floor.
        this.windowMs = settings.windowMs() > 0 ? settings.windowMs() : DEFAULT_WINDOW_MS;
        this.maxPointsPerAccount = Math.max(2, settings.maxPointsPerAccount());
        this.groupSize = Math.max(0, settings.groupSize());
        this.maxRetainedPoints = Math.max(2, settings.maxRetainedPoints());
        this.internalAccountsBelow = Math.max(0, settings.internalAccountsBelow());
    }

    /**
     * Record each account's starting equity. Call once after seeding: every later {@code relative}
     * is measured against this, so capturing it after trading has begun would understate the move.
     */
    public synchronized void captureBaselines() {
        for (BrokerRepository.AccountRow account : accounts.accounts()) {
            captureBaseline(account.accountNumber());
        }
    }

    /**
     * An account opened mid-session gets the same treatment the seeded ones get at startup: its equity
     * now is its baseline, so its curve starts at zero from the moment it exists rather than from its
     * first fill.
     */
    @Override
    public synchronized void onAccountOpened(String account, String ownerName) {
        captureBaseline(account);
    }

    private void captureBaseline(String account) {
        Equity equity = equity(account);
        baselines.put(account, equity.total());
        realized.putIfAbsent(account, BigDecimal.ZERO);
        tradeCounts.putIfAbsent(account, 0);
        // Anchor the curve at zero so a one-fill account still draws a line rather than a dot.
        series.computeIfAbsent(account, k -> new ArrayList<>()).add(new PnlPoint(
                0, clock.getAsLong(), scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO),
                scaled(BigDecimal.ZERO)));
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
        points.add(new PnlPoint(count, ts, scaled(realizedNow),
                scaled(relative.subtract(realizedNow)), scaled(relative)));
        evict(account, points, ts);
    }

    /**
     * Drop points that have left the window, and cap what a burst can retain inside it.
     *
     * <p>Driven by the sample's own timestamp rather than the clock, so a replayed or back-dated
     * series evicts by the same rule as a live one. Removal is from the head of an {@code ArrayList},
     * which is O(n) per point — fine at demo rates, and the alternative (a deque) would cost the random
     * access the downsampler wants.
     */
    private void evict(String account, List<PnlPoint> points, long now) {
        int removed = 0;
        long cutoff = now - windowMs;
        while (!points.isEmpty() && points.get(0).ts() < cutoff) {
            points.remove(0);
            removed++;
        }
        while (points.size() > maxRetainedPoints) {
            points.remove(0);
            removed++;
        }
        if (removed > 0) {
            dropped.merge(account, removed, Integer::sum);
        }
    }

    /**
     * Every account's P&amp;L: live totals for all of them, and the windowed curve for the busiest
     * {@code plotAccounts}.
     *
     * <p>Totals are computed here from live positions rather than read off the curve, so evicting
     * points cannot change a number in the table — only what the chart shows.
     */
    public synchronized List<AccountPnl> series() {
        long now = clock.getAsLong();
        // The broker's own accounts are excluded here rather than at the edge, so they cannot take a
        // slot in a group and then vanish from it — the selection below only ever sees customers.
        List<BrokerRepository.AccountRow> rows = accounts.accounts().stream()
                .filter(row -> !isInternal(row.accountNumber()))
                .toList();

        // Three questions, three groups: who is winning, who is trading, who is losing. Ties break on
        // account number so the selection is stable between polls — a curve that appeared and vanished
        // as two accounts swapped places would read as a bug in the chart.
        Map<String, BigDecimal> relatives = new java.util.HashMap<>();
        for (BrokerRepository.AccountRow row : rows) {
            Equity equity = equity(row.accountNumber());
            relatives.put(row.accountNumber(),
                    equity.total().subtract(baselines.getOrDefault(row.accountNumber(), equity.total())));
        }
        List<String> numbers = rows.stream().map(BrokerRepository.AccountRow::accountNumber).toList();
        // Best and worst are ranked among accounts that have actually traded. An account sitting at
        // exactly 0.00 because it never did anything — the issuer, a freshly opened investor — would
        // otherwise take the "best P&L" slots whenever the market is down, which says nothing about
        // performance. "Most active" needs no such filter: it is already a trade count.
        List<String> traders = numbers.stream()
                .filter(account -> tradeCounts.getOrDefault(account, 0) > 0)
                .toList();

        List<String> top = pick(traders, (a, b) -> {
            int byPnl = relatives.get(b).compareTo(relatives.get(a));
            return byPnl != 0 ? byPnl : a.compareTo(b);
        });
        List<String> active = pick(numbers, (a, b) -> {
            int byTrades = Integer.compare(tradeCounts.getOrDefault(b, 0), tradeCounts.getOrDefault(a, 0));
            return byTrades != 0 ? byTrades : a.compareTo(b);
        });
        List<String> bottom = pick(traders, (a, b) -> {
            int byPnl = relatives.get(a).compareTo(relatives.get(b));
            return byPnl != 0 ? byPnl : a.compareTo(b);
        });

        Map<String, List<String>> groups = new java.util.HashMap<>();
        for (String account : top) {
            groups.computeIfAbsent(account, k -> new ArrayList<>()).add(GROUP_TOP);
        }
        for (String account : active) {
            groups.computeIfAbsent(account, k -> new ArrayList<>()).add(GROUP_ACTIVE);
        }
        for (String account : bottom) {
            groups.computeIfAbsent(account, k -> new ArrayList<>()).add(GROUP_BOTTOM);
        }
        List<String> plotted = List.copyOf(groups.keySet());

        List<AccountPnl> out = new ArrayList<>();
        for (BrokerRepository.AccountRow account : rows) {
            String number = account.accountNumber();
            Equity equity = equity(number);
            BigDecimal baseline = baselines.getOrDefault(number, equity.total());
            BigDecimal relative = equity.total().subtract(baseline);
            BigDecimal realizedNow = realized.getOrDefault(number, BigDecimal.ZERO);

            List<PnlPoint> points = series.get(number);
            List<PnlPoint> payload = List.of();
            if (points != null && plotted.contains(number)) {
                // Evict on read too: an idle account's curve should age out of the window even though
                // no fill is arriving to trigger it.
                evict(number, points, now);
                payload = downsample(points, maxPointsPerAccount);
            }

            out.add(new AccountPnl(number, account.ownerName(), scaled(baseline), scaled(equity.total()),
                    scaled(realizedNow), scaled(relative.subtract(realizedNow)), scaled(relative),
                    tradeCounts.getOrDefault(number, 0), equity.unpriced(), windowMs,
                    dropped.getOrDefault(number, 0), plotted.contains(number),
                    List.copyOf(groups.getOrDefault(number, List.of())), payload));
        }
        return out;
    }

    /**
     * Is this one of the broker's own accounts (issuer, market makers) rather than a customer's?
     *
     * <p>By number, because the number is the one thing every layer already agrees on — no flag to set
     * at seeding time and no way for an account to be internal in one place and not another. An
     * account number that is not a number at all is treated as a customer's: hiding something because
     * it could not be parsed would be the wrong way round.
     */
    private boolean isInternal(String accountNumber) {
        if (internalAccountsBelow <= 0 || accountNumber == null) {
            return false;
        }
        try {
            return Long.parseLong(accountNumber.trim()) < internalAccountsBelow;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** The first {@code groupSize} accounts by a comparator — one group of the three. */
    private List<String> pick(List<String> numbers, java.util.Comparator<String> order) {
        return numbers.stream().sorted(order).limit(groupSize).toList();
    }

    /**
     * Thin a series to at most {@code budget} points, keeping the first and the last.
     *
     * <p>Stride sampling rather than a peak-preserving reduction: at 600 points across a chart about
     * 600 pixels wide there is no visible difference, and the endpoints — the two values a reader
     * actually checks against the axis — are exact. Cheap enough to run on every poll.
     */
    static List<PnlPoint> downsample(List<PnlPoint> points, int budget) {
        if (points.size() <= budget) {
            return List.copyOf(points);
        }
        List<PnlPoint> out = new ArrayList<>(budget);
        int last = points.size() - 1;
        // Spread `budget - 1` samples over the series, then append the final point exactly.
        double stride = (double) last / (budget - 1);
        for (int i = 0; i < budget - 1; i++) {
            out.add(points.get((int) Math.round(i * stride)));
        }
        out.add(points.get(last));
        return List.copyOf(out);
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
