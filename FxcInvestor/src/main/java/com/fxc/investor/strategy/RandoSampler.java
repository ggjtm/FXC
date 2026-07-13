package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * The {@code rando} price sampler (docs/stories/001-rando-agent.md): draws a price uniformly from
 * {@code [last*(1-band), last*(1+band)]}, with {@code band = 1%} by default. Returns empty when no
 * last-sale price is known yet.
 */
public final class RandoSampler implements PriceTargetSampler {

    private final double band;

    public RandoSampler() {
        this(0.01);
    }

    public RandoSampler(double band) {
        this.band = band;
    }

    @Override
    public Optional<BigDecimal> sample(String symbol, MarketView market, Random rng) {
        return market.lastSale(symbol).map(last -> {
            double factor = 1.0 + (rng.nextDouble() * 2.0 - 1.0) * band; // uniform in [1-band, 1+band]
            return last.multiply(BigDecimal.valueOf(factor));
        });
    }
}
