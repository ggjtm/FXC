package com.fxc.broker.pnl;

import com.fxc.broker.md.MarketDataCache;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Converts a currency amount to USD using the exchange's last sale for the matching spot pair
 * (docs/DESIGN.md §6). Needed because the broker's accounts hold several currencies but the console
 * plots one P&amp;L axis, which has to be in a single unit.
 *
 * <p>Resolution order for currency {@code CCY}: {@code USD} is 1; otherwise {@code CCY/USD} if it has
 * traded; otherwise the inverse of {@code USD/CCY}. The seeded universe (EUR/USD, GBP/USD, AUD/USD,
 * USD/JPY) covers every currency the demo uses — EUR, GBP and AUD directly, JPY inverted.
 *
 * <p>A currency with no usable rate returns empty rather than a guess. Callers count those holdings
 * and report the count, so an unconvertible balance shows up as a disclosed gap instead of silently
 * valuing at zero.
 */
public final class FxRates {

    private static final int SCALE = 10;
    private static final String USD = "USD";

    private final MarketDataCache marketData;

    public FxRates(MarketDataCache marketData) {
        this.marketData = marketData;
    }

    /** The value of one unit of {@code currency} in USD, if it can be determined. */
    public Optional<BigDecimal> toUsd(String currency) {
        if (USD.equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        Optional<BigDecimal> direct = marketData.lastPrice(currency + "/" + USD);
        if (direct.isPresent() && direct.get().signum() != 0) {
            return direct;
        }
        Optional<BigDecimal> inverse = marketData.lastPrice(USD + "/" + currency);
        if (inverse.isPresent() && inverse.get().signum() != 0) {
            return Optional.of(BigDecimal.ONE.divide(inverse.get(), SCALE, RoundingMode.HALF_UP));
        }
        return Optional.empty();
    }

    /** Convert an amount in {@code currency} to USD, or empty when no rate is available. */
    public Optional<BigDecimal> convert(BigDecimal amount, String currency) {
        return toUsd(currency).map(rate -> amount.multiply(rate));
    }
}
