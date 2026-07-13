package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * The common agent shell for {@code rando}/{@code booker}/{@code bookfish} (docs/stories/): random
 * side (50/50) and random quantity (1–10 units), with the limit price drawn by a pluggable
 * {@link PriceTargetSampler}. Only the sampler differs between the three agents.
 */
public final class SamplingStrategy implements Strategy {

    private static final int MIN_QTY = 1;
    private static final int MAX_QTY = 10;

    private final String name;
    private final PriceTargetSampler sampler;

    public SamplingStrategy(String name, PriceTargetSampler sampler) {
        this.name = name;
        this.sampler = sampler;
    }

    public String name() {
        return name;
    }

    @Override
    public Optional<OrderIntent> decide(String symbol, MarketView market, PortfolioView portfolio, Random rng) {
        Optional<BigDecimal> price = sampler.sample(symbol, market, rng);
        if (price.isEmpty()) {
            return Optional.empty();
        }
        Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
        BigDecimal quantity = BigDecimal.valueOf(MIN_QTY + rng.nextInt(MAX_QTY - MIN_QTY + 1));
        return Optional.of(new OrderIntent(side, price.get(), quantity));
    }
}
