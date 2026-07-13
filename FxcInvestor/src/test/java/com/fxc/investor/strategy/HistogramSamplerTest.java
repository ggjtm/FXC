package com.fxc.investor.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for {@code booker} (docs/stories/002) and {@code bookfish} (docs/stories/003).
 *
 * <p>Test histogram: prices 41.90/42.00/42.10/42.20/42.30 weighted 10/30/50/30/10, last sale 42.10.
 * Weighted σ ≈ 0.1039. So the 1σ band (booker) admits {42.00, 42.10, 42.20}; the 0.5σ band
 * (bookfish) admits only {42.10}.
 */
class HistogramSamplerTest {

    private static final String SYMBOL = "ACME";
    private static final BigDecimal LAST = new BigDecimal("42.10");

    private static final BigDecimal P1 = new BigDecimal("41.90");
    private static final BigDecimal P2 = new BigDecimal("42.00");
    private static final BigDecimal P3 = new BigDecimal("42.10");
    private static final BigDecimal P4 = new BigDecimal("42.20");
    private static final BigDecimal P5 = new BigDecimal("42.30");

    private MarketView withTradedVolume() {
        MarketView m = new MarketView();
        m.recordTrade(SYMBOL, P1, new BigDecimal("10"));
        m.recordTrade(SYMBOL, P2, new BigDecimal("30"));
        m.recordTrade(SYMBOL, P3, new BigDecimal("50"));
        m.recordTrade(SYMBOL, P4, new BigDecimal("30"));
        m.recordTrade(SYMBOL, P5, new BigDecimal("10"));
        m.setLastSale(SYMBOL, LAST); // pin the filter center regardless of record order
        return m;
    }

    private MarketView withBook() {
        MarketView m = new MarketView();
        m.setLastSale(SYMBOL, LAST);
        m.setBook(SYMBOL, List.of(
                new MarketView.Level(P1, new BigDecimal("10")),
                new MarketView.Level(P2, new BigDecimal("30")),
                new MarketView.Level(P3, new BigDecimal("50")),
                new MarketView.Level(P4, new BigDecimal("30")),
                new MarketView.Level(P5, new BigDecimal("10"))));
        return m;
    }

    @Test
    void bookfishStaysWithinHalfSigmaOfLastSale() {
        BookfishSampler sampler = new BookfishSampler();
        MarketView market = withTradedVolume();
        Random rng = new Random(11);
        for (int i = 0; i < 500; i++) {
            BigDecimal price = sampler.sample(SYMBOL, market, rng).orElseThrow();
            // 0.5σ band admits only 42.10.
            assertEquals(0, price.compareTo(P3), "bookfish price " + price + " should be 42.10 (only 0.5σ bin)");
        }
    }

    @Test
    void bookerStaysWithinOneSigmaAndFavorsHeaviestBin() {
        BookerSampler sampler = new BookerSampler();
        MarketView market = withBook();
        Random rng = new Random(13);
        int[] counts = new int[3]; // 42.00, 42.10, 42.20
        for (int i = 0; i < 6000; i++) {
            BigDecimal price = sampler.sample(SYMBOL, market, rng).orElseThrow();
            boolean inBand = price.compareTo(P2) == 0 || price.compareTo(P3) == 0 || price.compareTo(P4) == 0;
            assertTrue(inBand, "booker price " + price + " should be within 1σ {42.00,42.10,42.20}");
            if (price.compareTo(P2) == 0) counts[0]++;
            else if (price.compareTo(P3) == 0) counts[1]++;
            else counts[2]++;
        }
        assertTrue(counts[1] > counts[0] && counts[1] > counts[2],
                "42.10 (heaviest bin) should be drawn most often: " + java.util.Arrays.toString(counts));
    }

    @Test
    void emptyHistogramFallsBackToRandoBand() {
        BookfishSampler sampler = new BookfishSampler();
        MarketView market = new MarketView();
        market.setLastSale(SYMBOL, LAST); // no trades recorded
        Random rng = new Random(5);
        BigDecimal low = new BigDecimal("41.679");
        BigDecimal high = new BigDecimal("42.521");
        for (int i = 0; i < 200; i++) {
            BigDecimal price = sampler.sample(SYMBOL, market, rng).orElseThrow();
            assertTrue(price.compareTo(low) >= 0 && price.compareTo(high) <= 0,
                    "fallback price " + price + " should be within ±1% of last sale");
        }
    }

    @Test
    void noLastSaleMeansNoSignal() {
        assertEquals(Optional.empty(), new BookerSampler().sample(SYMBOL, new MarketView(), new Random(1)));
        assertEquals(Optional.empty(), new BookfishSampler().sample(SYMBOL, new MarketView(), new Random(1)));
    }
}
