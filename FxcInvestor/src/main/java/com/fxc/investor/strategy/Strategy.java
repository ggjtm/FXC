package com.fxc.investor.strategy;

import java.util.Optional;
import java.util.Random;

/**
 * The pluggable decision function for an autonomous agent (docs/DESIGN.md §4.4). Deterministic
 * given the same inputs and RNG state, so agents are reproducible and unit-testable.
 *
 * @return an order to submit, or empty to do nothing this tick.
 */
@FunctionalInterface
public interface Strategy {

    Optional<OrderIntent> decide(String symbol, MarketView market, PortfolioView portfolio, Random rng);
}
