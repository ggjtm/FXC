package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * The pluggable price-target seam shared by {@code rando}/{@code booker}/{@code bookfish}
 * (docs/stories/). The three agents differ only in how they draw a raw limit price; side and
 * quantity are common (see {@link SamplingStrategy}).
 *
 * @return a raw price target (not yet tick-snapped), or empty if there is no signal to trade on.
 */
@FunctionalInterface
public interface PriceTargetSampler {

    Optional<BigDecimal> sample(String symbol, MarketView market, Random rng);
}
