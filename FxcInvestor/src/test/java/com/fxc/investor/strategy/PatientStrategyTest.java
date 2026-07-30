package com.fxc.investor.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * {@code bookfish}'s patience gate (docs/stories/003). Twin of {@code loadgen/tests/test_patience.py} —
 * same cases, same numbers, so {@code bookfish} cannot come to mean two different things in the two
 * languages.
 *
 * <p>The behaviour under test is an <em>abstention</em>, which is easy to get wrong in a way nothing
 * complains about: too strict and the agent never trades, too loose and it is the uniform fallback
 * again (i.e. {@code rando} under another name). So these tests pin both directions — that it waits,
 * and that it eventually acts.
 */
class PatientStrategyTest {

    private static final String SYMBOL = "ACME";
    private static final BigDecimal TICK = new BigDecimal("0.01");

    /** A stand-in sampler that always wants the same thing, so the gate is what is under test. */
    private static final class Fixed implements Strategy {
        private OrderIntent intent;
        private final int draws;

        Fixed(OrderIntent intent) {
            this(intent, 0);
        }

        Fixed(OrderIntent intent, int draws) {
            this.intent = intent;
            this.draws = draws;
        }

        @Override
        public Optional<OrderIntent> decide(String symbol, MarketView market,
                                            PortfolioView portfolio, Random rng) {
            for (int i = 0; i < draws; i++) {
                rng.nextDouble();
            }
            return Optional.ofNullable(intent);
        }
    }

    private static OrderIntent intent(Side side, String price) {
        return new OrderIntent(side, new BigDecimal(price), new BigDecimal("5"));
    }

    /** Two bins, 80 total volume, mean exactly 42.10 — ready, with a symmetric fair value. */
    private static MarketView ready() {
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("42.00"), new BigDecimal("40"));
        market.recordTrade(SYMBOL, new BigDecimal("42.20"), new BigDecimal("40"));
        market.setLastSale(SYMBOL, new BigDecimal("42.10"));
        return market;
    }

    // --- readiness ---

    @Test
    void abstainsWithNoObservationsAtAll() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")));
        MarketView cold = new MarketView();
        cold.setLastSale(SYMBOL, new BigDecimal("42.10"));
        assertTrue(gate.decide(SYMBOL, cold, PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NOT_READY, gate.lastReason());
    }

    @Test
    void abstainsOnASinglePriceBin() {
        // One bin cannot express a distribution; this is the case the sampler papers over.
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("42.10"), new BigDecimal("5000"));
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")));
        assertTrue(gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NOT_READY, gate.lastReason());
    }

    @Test
    void abstainsBelowTheVolumeThreshold() {
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("42.00"), new BigDecimal("3"));
        market.recordTrade(SYMBOL, new BigDecimal("42.20"), new BigDecimal("4")); // 7, limit is 50
        market.setLastSale(SYMBOL, new BigDecimal("42.10"));
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")));
        assertTrue(gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NOT_READY, gate.lastReason());
    }

    @Test
    void tradesOnceTheThresholdIsMet() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isPresent());
        assertNull(gate.lastReason());
    }

    @Test
    void volumeThresholdIsConfigurable() {
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("42.00"), new BigDecimal("3"));
        market.recordTrade(SYMBOL, new BigDecimal("42.20"), new BigDecimal("4"));
        market.setLastSale(SYMBOL, new BigDecimal("42.10"));
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")),
                new BigDecimal("5"), PatientStrategy.DEFAULT_MIN_EDGE_TICKS);
        assertTrue(gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(1)).isPresent());
    }

    @Test
    void aDelegateWithNoOpinionIsReportedSeparately() {
        // No last sale: the sampler declines before patience is relevant, and the reason must say so
        // rather than blaming the histogram.
        PatientStrategy gate = new PatientStrategy(new Fixed(null));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NO_OPINION, gate.lastReason());
    }

    @Test
    void unknownInstrumentDeclinesRatherThanInventingAMargin() {
        MarketView market = new MarketView();
        market.recordTrade("NOPE", new BigDecimal("1.10"), new BigDecimal("40"));
        market.recordTrade("NOPE", new BigDecimal("1.30"), new BigDecimal("40"));
        market.setLastSale("NOPE", new BigDecimal("1.20"));
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "1.10")));
        assertTrue(gate.decide("NOPE", market, PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NOT_READY, gate.lastReason());
    }

    // --- edge ---

    @Test
    void buysBelowFairValue() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.00")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isPresent());
    }

    @Test
    void willNotBuyAboveFairValue() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.20")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NO_EDGE, gate.lastReason());
    }

    @Test
    void sellsAboveFairValue() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.SELL, "42.20")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isPresent());
    }

    @Test
    void willNotSellBelowFairValue() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.SELL, "42.00")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NO_EDGE, gate.lastReason());
    }

    @Test
    void tradingAtFairValueIsNotAnAdvantage() {
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.10")));
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isEmpty());
        assertEquals(PatientStrategy.NO_EDGE, gate.lastReason());
    }

    @Test
    void marginIsMeasuredInTicks() {
        // ACME's tick is 0.01, so a 0.05 edge clears 1 tick and 5 ticks, but not 6.
        MarketView market = ready();
        for (int[] expectation : new int[][] {{1, 1}, {5, 1}, {6, 0}}) {
            PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.05")),
                    PatientStrategy.DEFAULT_MIN_VOLUME, expectation[0]);
            boolean traded = gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(1))
                    .isPresent();
            assertEquals(expectation[1] == 1, traded, "minEdgeTicks=" + expectation[0]);
        }
    }

    @Test
    void zeroTicksDisablesTheEdgeGate() {
        // Documented escape hatch: readiness only, i.e. the pre-patience behaviour.
        PatientStrategy gate = new PatientStrategy(new Fixed(intent(Side.BUY, "42.20")),
                PatientStrategy.DEFAULT_MIN_VOLUME, 0);
        assertTrue(gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).isPresent());
        assertNull(gate.lastReason());
    }

    @Test
    void theIntentIsPassedThroughUntouched() {
        OrderIntent drawn = intent(Side.BUY, "42.00");
        PatientStrategy gate = new PatientStrategy(new Fixed(drawn));
        assertSame(drawn,
                gate.decide(SYMBOL, ready(), PortfolioView.empty(), new Random(1)).orElseThrow());
    }

    // --- the draw must not depend on the decision ---

    @Test
    void abstainingConsumesTheSameDrawsAsTrading() {
        // If patience shortened the RNG sequence, a seeded run's later decisions would diverge
        // depending on whether earlier ones happened to find an edge.
        PatientStrategy favourable =
                new PatientStrategy(new Fixed(intent(Side.BUY, "42.00"), 3));
        PatientStrategy unfavourable =
                new PatientStrategy(new Fixed(intent(Side.BUY, "42.20"), 3));
        MarketView market = ready();

        Random a = new Random(7);
        favourable.decide(SYMBOL, market, PortfolioView.empty(), a);
        Random b = new Random(7);
        unfavourable.decide(SYMBOL, market, PortfolioView.empty(), b);

        assertEquals(a.nextDouble(), b.nextDouble());
    }

    // --- the real sampler through the real gate ---

    /**
     * A market shaped like the demo's: {@code rando} alone spreads fills over ±1% of the last sale, so
     * the traded-volume histogram spans dollars rather than ticks (σ ≈ 0.24 on a 42.10 market).
     */
    private static MarketView liveMarket() {
        MarketView market = new MarketView();
        for (int step = 0; step <= 20; step++) {
            BigDecimal price = new BigDecimal("41.60").add(new BigDecimal("0.05")
                    .multiply(BigDecimal.valueOf(step)));
            market.recordTrade(SYMBOL, price, BigDecimal.valueOf(100 - Math.abs(step - 10) * 8L));
        }
        market.setLastSale(SYMBOL, new BigDecimal("42.10"));
        return market;
    }

    @Test
    void realBookfishTradesSometimesAndWaitsSometimes() {
        Strategy bookfish = new SamplingStrategy("bookfish", new BookfishSampler());
        PatientStrategy gate = new PatientStrategy(bookfish);
        MarketView market = liveMarket();
        int traded = 0;
        for (int seed = 0; seed < 300; seed++) {
            if (gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(seed)).isPresent()) {
                traded++;
            }
        }
        // Roughly a third to a half. Wide bounds, because the point is that neither extreme (never
        // trades / always trades) is what happens.
        assertTrue(traded > 40, "patience must not starve the agent, traded=" + traded);
        assertTrue(traded < 260, "patience must actually decline sometimes, traded=" + traded);
    }

    @Test
    void everySubmittedOrderHasAnEdge() {
        Strategy bookfish = new SamplingStrategy("bookfish", new BookfishSampler());
        PatientStrategy gate = new PatientStrategy(bookfish);
        MarketView market = liveMarket();
        BigDecimal fair = BigDecimal.valueOf(
                HistogramSampling.stats(market.tradedVolume(SYMBOL)).mean());
        for (int seed = 0; seed < 300; seed++) {
            Optional<OrderIntent> decision =
                    gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(seed));
            if (decision.isEmpty()) {
                continue;
            }
            OrderIntent order = decision.get();
            if (order.side() == Side.BUY) {
                assertTrue(order.price().compareTo(fair.subtract(TICK)) <= 0,
                        "seed " + seed + " bought at " + order.price() + " vs fair " + fair);
            } else {
                assertTrue(order.price().compareTo(fair.add(TICK)) >= 0,
                        "seed " + seed + " sold at " + order.price() + " vs fair " + fair);
            }
        }
    }

    @Test
    void aColdMarketWaitsInsteadOfFallingBackToRando() {
        // The behaviour this whole gate exists for: no volume seen yet, so no orders — where the bare
        // sampler would have drawn uniformly around the last sale and looked like rando.
        MarketView cold = new MarketView();
        cold.setLastSale(SYMBOL, new BigDecimal("42.10"));
        Strategy bare = new SamplingStrategy("bookfish", new BookfishSampler());
        PatientStrategy gate = new PatientStrategy(bare);
        for (int seed = 0; seed < 50; seed++) {
            assertTrue(gate.decide(SYMBOL, cold, PortfolioView.empty(), new Random(seed)).isEmpty());
            assertTrue(bare.decide(SYMBOL, cold, PortfolioView.empty(), new Random(seed)).isPresent());
        }
    }

    @Test
    void waitsIndefinitelyWhenTheOnlyReachablePriceIsFairValue() {
        // The degenerate case, worth stating outright: all the volume sits at the last sale and 0.5σ is
        // tighter than the gap to its neighbours, so the only price bookfish can draw is fair value
        // itself. No advantage is available, so it never trades — by design, not by defect. (This is
        // the same histogram HistogramSamplerTest uses, where the 0.5σ band admits only 42.10.)
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("41.90"), new BigDecimal("10"));
        market.recordTrade(SYMBOL, new BigDecimal("42.00"), new BigDecimal("30"));
        market.recordTrade(SYMBOL, new BigDecimal("42.10"), new BigDecimal("50"));
        market.recordTrade(SYMBOL, new BigDecimal("42.20"), new BigDecimal("30"));
        market.recordTrade(SYMBOL, new BigDecimal("42.30"), new BigDecimal("10"));
        market.setLastSale(SYMBOL, new BigDecimal("42.10"));
        PatientStrategy gate =
                new PatientStrategy(new SamplingStrategy("bookfish", new BookfishSampler()));
        for (int seed = 0; seed < 50; seed++) {
            assertTrue(gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(seed)).isEmpty());
            assertEquals(PatientStrategy.NO_EDGE, gate.lastReason());
        }
    }

    @Test
    void actsOnceTheMarketMovesAwayFromFairValue() {
        // Same histogram, but the last sale has drifted below where the volume traded: every in-band
        // price is now below fair value, so every BUY draw has an edge. This is what makes patience
        // productive rather than merely quiet.
        MarketView market = new MarketView();
        market.recordTrade(SYMBOL, new BigDecimal("41.90"), new BigDecimal("10"));
        market.recordTrade(SYMBOL, new BigDecimal("42.00"), new BigDecimal("30"));
        market.recordTrade(SYMBOL, new BigDecimal("42.10"), new BigDecimal("50"));
        market.recordTrade(SYMBOL, new BigDecimal("42.20"), new BigDecimal("30"));
        market.recordTrade(SYMBOL, new BigDecimal("42.30"), new BigDecimal("10"));
        market.setLastSale(SYMBOL, new BigDecimal("41.95"));
        PatientStrategy gate =
                new PatientStrategy(new SamplingStrategy("bookfish", new BookfishSampler()));
        int traded = 0;
        for (int seed = 0; seed < 200; seed++) {
            Optional<OrderIntent> decision =
                    gate.decide(SYMBOL, market, PortfolioView.empty(), new Random(seed));
            if (decision.isPresent()) {
                traded++;
                assertEquals(Side.BUY, decision.get().side(), "only the cheap side has an edge here");
            }
        }
        assertTrue(traded > 40, "traded=" + traded);
    }

    // --- wiring ---

    @Test
    void byNameWrapsBookfishInPatienceInsideLiquidity() {
        Strategy bookfish = Strategies.byName("bookfish");
        assertTrue(bookfish instanceof LiquidityAwareStrategy);
        // Registry order matters: liquidity outermost so it can size what patience approved.
        Strategy inner = ((LiquidityAwareStrategy) bookfish).delegate();
        assertTrue(inner instanceof PatientStrategy, "expected patience inside liquidity, got " + inner);
    }

    @Test
    void bookerAndRandoAreNotPatient() {
        Strategy booker = Strategies.byName("booker");
        assertTrue(booker instanceof LiquidityAwareStrategy);
        assertFalse(((LiquidityAwareStrategy) booker).delegate() instanceof PatientStrategy);
        assertFalse(Strategies.byName("rando") instanceof PatientStrategy);
    }

    @Test
    void liquidityStillForcesASellAfterAnAbstention() {
        // Patience *delays* a forced liquidity sell; it must not prevent one. Three ticks: establish
        // the cash baseline, abstain while the account is already under water, then act.
        Fixed sampler = new Fixed(intent(Side.BUY, "42.00"));
        PatientStrategy gate = new PatientStrategy(sampler);
        LiquidityAwareStrategy policy = new LiquidityAwareStrategy(gate);
        MarketView market = ready();
        PortfolioView healthy = new PortfolioView(
                java.util.Map.of("USD", new BigDecimal("1000")),
                java.util.Map.of("ACME", new BigDecimal("500")));
        PortfolioView drained = new PortfolioView(
                java.util.Map.of("USD", new BigDecimal("100")),
                java.util.Map.of("ACME", new BigDecimal("500")));

        assertTrue(policy.decide(SYMBOL, market, healthy, new Random(1)).isPresent());

        sampler.intent = null;
        assertTrue(policy.decide(SYMBOL, market, drained, new Random(1)).isEmpty());

        sampler.intent = intent(Side.BUY, "42.00");
        OrderIntent forced = policy.decide(SYMBOL, market, drained, new Random(1)).orElseThrow();
        assertEquals(Side.SELL, forced.side());
    }
}
