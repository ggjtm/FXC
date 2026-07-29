package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The agent's current holdings as last known from OFX statements (docs/DESIGN.md §4.4). Strategies
 * may use it to avoid, e.g., selling shares they do not hold; {@code rando} ignores it.
 *
 * @param cashByCurrency currency code -> balance
 * @param shares         symbol -> share quantity
 */
public record PortfolioView(Map<String, BigDecimal> cashByCurrency, Map<String, BigDecimal> shares) {

    public static PortfolioView empty() {
        return new PortfolioView(Map.of(), Map.of());
    }

    public BigDecimal shares(String symbol) {
        return shares.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /**
     * Balance in one currency, or zero. The liquidity-managed strategies size buys against this, so
     * an absent currency reads as "no funds" rather than being an error.
     */
    public BigDecimal cash(String currency) {
        return cashByCurrency.getOrDefault(currency, BigDecimal.ZERO);
    }

    /** True when nothing is known yet — i.e. {@link #empty()} or a statement that reported nothing. */
    public boolean isEmpty() {
        return cashByCurrency.isEmpty() && shares.isEmpty();
    }
}
