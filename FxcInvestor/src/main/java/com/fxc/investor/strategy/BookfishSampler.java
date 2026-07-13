package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * The {@code bookfish} price sampler (docs/stories/003-bookfish-agent.md): draws a price from a
 * histogram of volume traded at each price, restricted to within 0.5σ of the last sale — a tighter
 * band than {@code booker}. An empty histogram falls back to {@code rando}'s ±1% behavior.
 *
 * <p>The traded-volume histogram is accumulated in {@link MarketView#recordTrade} from the fills
 * the agent observes on the FxcPub feed — so {@code bookfish} is self-contained for a pure
 * OFX/XMPP investor.
 */
public final class BookfishSampler implements PriceTargetSampler {

    private static final double SIGMA = 0.5;
    private static final double FALLBACK_BAND = 0.01;

    @Override
    public Optional<BigDecimal> sample(String symbol, MarketView market, Random rng) {
        Optional<BigDecimal> lastSale = market.lastSale(symbol);
        if (lastSale.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(HistogramSampling.sample(
                market.tradedVolume(symbol), lastSale.get(), SIGMA, FALLBACK_BAND, rng));
    }
}
