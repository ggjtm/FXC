package com.fxc.investor.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Acceptance tests for the {@code rando} agent (docs/stories/001-rando-agent.md). */
class RandoStrategyTest {

    private static final String SYMBOL = "ACME";
    private static final BigDecimal LAST = new BigDecimal("42.10");
    private static final BigDecimal LOW = new BigDecimal("41.679");   // 42.10 * 0.99
    private static final BigDecimal HIGH = new BigDecimal("42.521");  // 42.10 * 1.01

    private final Strategy rando = new SamplingStrategy("rando", new RandoSampler());

    private MarketView marketWithLast() {
        MarketView m = new MarketView();
        m.setLastSale(SYMBOL, LAST);
        return m;
    }

    @Test
    void priceWithinOnePercentAndQuantityInRange() {
        MarketView market = marketWithLast();
        Random rng = new Random(1);
        for (int i = 0; i < 2000; i++) {
            OrderIntent d = rando.decide(SYMBOL, market, PortfolioView.empty(), rng).orElseThrow();
            assertTrue(d.price().compareTo(LOW) >= 0 && d.price().compareTo(HIGH) <= 0,
                    "price " + d.price() + " should be within +/-1% of " + LAST);
            int qty = d.quantity().intValueExact();
            assertTrue(qty >= 1 && qty <= 10, "quantity " + qty + " should be in [1,10]");
        }
    }

    @Test
    void buyAndSellAreRoughlyBalanced() {
        MarketView market = marketWithLast();
        Random rng = new Random(3);
        int buys = 0;
        int total = 4000;
        for (int i = 0; i < total; i++) {
            if (rando.decide(SYMBOL, market, PortfolioView.empty(), rng).orElseThrow().side() == Side.BUY) {
                buys++;
            }
        }
        double ratio = buys / (double) total;
        assertTrue(ratio > 0.4 && ratio < 0.6, "buy ratio " + ratio + " should be ~0.5");
    }

    @Test
    void reproducibleWithFixedSeed() {
        List<String> a = decisions(new Random(7));
        List<String> b = decisions(new Random(7));
        assertEquals(a, b, "same seed should yield the same decision sequence");
    }

    @Test
    void noLastSaleMeansNoDecision() {
        Optional<OrderIntent> d = rando.decide(SYMBOL, new MarketView(), PortfolioView.empty(), new Random(1));
        assertTrue(d.isEmpty(), "with no last-sale signal, rando should not trade");
    }

    private List<String> decisions(Random rng) {
        MarketView market = marketWithLast();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            OrderIntent d = rando.decide(SYMBOL, market, PortfolioView.empty(), rng).orElseThrow();
            out.add(d.side() + ":" + d.quantity() + ":" + d.price());
        }
        return out;
    }
}
