package com.fxc.investor.strategy;

import com.fxc.common.instrument.AssetClass;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a strategy sustainable indefinitely: scale buying to available cash, and sell assets to keep
 * some liquidity (Jeremy's call, 2026-07-29 — docs/DESIGN.md §6).
 *
 * <p><b>Why.</b> The seeded demo accounts hold 1,000 shares and $1,000,000. A one-sided stream
 * exhausts one or the other within minutes and then just produces rejections, which is what stopped
 * the demo from running continuously. A naive strategy has no way to know; this decorator gives the
 * non-naive ones ({@code booker}, {@code bookfish}) the three corrections they need.
 *
 * <p><b>What it does</b>, in order:
 * <ol>
 *   <li><b>Restore liquidity.</b> When cash has fallen below {@code cashFloorFraction} of the cash
 *       first observed, force a SELL sized to bring it back to the floor.</li>
 *   <li><b>Cap buys by affordable cash.</b> Only cash *above* the floor is spendable, and no single
 *       order may commit more than {@code buyBudgetFraction} of it — so buying can never itself push
 *       the account under the floor.</li>
 *   <li><b>Cap sells by holdings.</b> There is no shorting: the broker rejects a sell of more than is
 *       held, so the quantity is clamped instead of rejected.</li>
 * </ol>
 *
 * <p>In normal operation none of these bind — the samplers ask for 1–10 units, which is a rounding
 * error against $1,000,000 — so the decorator is invisible until the account actually approaches a
 * limit. That is deliberate: it should change the outcome only where the naive version would have
 * been rejected.
 *
 * <p><b>Applies to equities and FX alike.</b> For an equity the sell bound is the share position; for
 * an FX pair it is the base-currency balance, since an FX sell delivers base currency. Buying is
 * capped against the quote currency in both cases.
 *
 * <p><b>Fails closed.</b> With no portfolio data — before the first statement read succeeds, or if the
 * broker is unreachable — this declines to trade rather than guessing. See {@code PortfolioCache}.
 *
 * <p>Wrapped around a sampler rather than folded into {@link SamplingStrategy} so that {@code rando}
 * is provably untouched: its tests pass the bare strategy and never see this class.
 */
public final class LiquidityAwareStrategy implements Strategy {

    /** No single order may commit more than this share of spendable cash. */
    public static final BigDecimal DEFAULT_BUY_BUDGET_FRACTION = new BigDecimal("0.10");
    /** Cash is kept at or above this share of the cash first observed. */
    public static final BigDecimal DEFAULT_CASH_FLOOR_FRACTION = new BigDecimal("0.25");

    private static final int SCALE = 8;

    private final Strategy delegate;
    private final BigDecimal buyBudgetFraction;
    private final BigDecimal cashFloorFraction;

    /** Cash per currency the first time it was seen — the reference the floor is measured against. */
    private final Map<String, BigDecimal> baselineCash = new ConcurrentHashMap<>();

    public LiquidityAwareStrategy(Strategy delegate) {
        this(delegate, DEFAULT_BUY_BUDGET_FRACTION, DEFAULT_CASH_FLOOR_FRACTION);
    }

    public LiquidityAwareStrategy(Strategy delegate, BigDecimal buyBudgetFraction,
                                  BigDecimal cashFloorFraction) {
        this.delegate = delegate;
        this.buyBudgetFraction = buyBudgetFraction;
        this.cashFloorFraction = cashFloorFraction;
    }

    @Override
    public Optional<OrderIntent> decide(String symbol, MarketView market, PortfolioView portfolio,
                                        Random rng) {
        Optional<OrderIntent> raw = delegate.decide(symbol, market, portfolio, rng);
        if (raw.isEmpty()) {
            return raw;
        }
        if (portfolio == null || portfolio.isEmpty()) {
            return Optional.empty(); // no holdings data — decline rather than guess
        }
        Instrument instrument = InstrumentCatalog.find(symbol).orElse(null);
        if (instrument == null) {
            return Optional.empty();
        }

        OrderIntent intent = raw.get();
        BigDecimal price = intent.price();
        if (price == null || price.signum() <= 0) {
            return Optional.empty();
        }

        String quoteCurrency = instrument.quoteCurrency().getCurrencyCode();
        BigDecimal cash = portfolio.cash(quoteCurrency);
        BigDecimal baseline = baselineCash.computeIfAbsent(quoteCurrency, c -> cash);
        BigDecimal floor = baseline.multiply(cashFloorFraction);
        BigDecimal sellable = sellableQuantity(instrument, symbol, portfolio);

        // 1. Below the floor: raise cash by selling, whatever the sampler wanted to do.
        if (cash.compareTo(floor) < 0 && sellable.signum() > 0) {
            BigDecimal shortfall = floor.subtract(cash);
            BigDecimal needed = shortfall.divide(price, SCALE, RoundingMode.CEILING);
            // Round the requirement UP onto the lot grid, then clamp to holdings. Flooring here would
            // undershoot the floor by up to a lot and never quite restore liquidity; clamping second
            // keeps it inside what is actually held (no shorting).
            BigDecimal quantity = snapUp(instrument, needed).min(snapDown(instrument, sellable));
            if (quantity.signum() <= 0) {
                return Optional.empty();
            }
            return Optional.of(new OrderIntent(Side.SELL, price, quantity));
        }

        // 2/3. Otherwise keep the sampler's side and only clamp the quantity to what is possible.
        if (intent.side() == Side.BUY) {
            BigDecimal spendable = cash.subtract(floor).max(BigDecimal.ZERO).multiply(buyBudgetFraction);
            BigDecimal affordable = snapDown(instrument, spendable.divide(price, SCALE, RoundingMode.DOWN));
            BigDecimal quantity = intent.quantity().min(affordable);
            if (quantity.signum() <= 0) {
                return Optional.empty(); // cannot fund even one lot
            }
            return quantity.compareTo(intent.quantity()) == 0
                    ? raw
                    : Optional.of(new OrderIntent(Side.BUY, price, quantity));
        }

        BigDecimal quantity = snapDown(instrument, intent.quantity().min(sellable));
        if (quantity.signum() <= 0) {
            return Optional.empty(); // nothing to sell — the broker would reject a short
        }
        return quantity.compareTo(intent.quantity()) == 0
                ? raw
                : Optional.of(new OrderIntent(Side.SELL, price, quantity));
    }

    /**
     * How much of {@code symbol} can be sold: the share position for an equity, the base-currency
     * balance for an FX pair (an FX sell delivers base currency).
     */
    private static BigDecimal sellableQuantity(Instrument instrument, String symbol,
                                               PortfolioView portfolio) {
        if (instrument.assetClass() == AssetClass.EQUITY) {
            return portfolio.shares(symbol);
        }
        FxSpotInstrument fx = (FxSpotInstrument) instrument;
        return portfolio.cash(fx.baseCurrency().getCurrencyCode());
    }

    /** Round down onto the lot grid — for anything bounded by cash or holdings. */
    private static BigDecimal snapDown(Instrument instrument, BigDecimal quantity) {
        BigDecimal lot = instrument.lotSize();
        if (lot.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(lot, 0, RoundingMode.FLOOR).multiply(lot);
    }

    /** Round up onto the lot grid — for a requirement that must actually be met. */
    private static BigDecimal snapUp(Instrument instrument, BigDecimal quantity) {
        BigDecimal lot = instrument.lotSize();
        if (lot.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(lot, 0, RoundingMode.CEILING).multiply(lot);
    }
}
