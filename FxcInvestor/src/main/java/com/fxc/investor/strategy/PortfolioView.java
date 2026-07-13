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
}
