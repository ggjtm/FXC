package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The {@code booker} price sampler (docs/stories/002-booker-agent.md): draws a price from a
 * quantity-weighted histogram of the current order book, restricted to within 1σ of the last sale.
 * A degenerate/empty book falls back to {@code rando}'s ±1% behavior.
 *
 * <p>The order-book depth must be supplied into {@link MarketView#setBook} (e.g. from the
 * broker order-book-snapshot relay — see FxcBroker/docs/stories/001 — or the simulation harness).
 */
public final class BookerSampler implements PriceTargetSampler {

    private static final double SIGMA = 1.0;
    private static final double FALLBACK_BAND = 0.01;

    @Override
    public Optional<BigDecimal> sample(String symbol, MarketView market, Random rng) {
        Optional<BigDecimal> lastSale = market.lastSale(symbol);
        if (lastSale.isEmpty()) {
            return Optional.empty();
        }
        Map<BigDecimal, BigDecimal> histogram = new HashMap<>();
        for (MarketView.Level level : market.book(symbol)) {
            histogram.merge(level.price(), level.quantity(), BigDecimal::add);
        }
        return Optional.of(HistogramSampling.sample(histogram, lastSale.get(), SIGMA, FALLBACK_BAND, rng));
    }
}
