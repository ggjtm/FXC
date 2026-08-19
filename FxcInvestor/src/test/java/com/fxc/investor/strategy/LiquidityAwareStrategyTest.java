package com.fxc.investor.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The liquidity policy. Twin of {@code loadgen/tests/test_liquidity.py} — same cases, same numbers, so
 * {@code booker} cannot come to mean two different things in the two languages.
 *
 * <p>The policy exists so a run continues indefinitely instead of exhausting one side of the seeded
 * account and degrading into a reject stream, so most of these tests are about the boundaries: cash
 * running out, holdings running out, and having no data at all.
 */
class LiquidityAwareStrategyTest {

    private static final Random RNG = new Random(1);
    private static final MarketView MARKET = new MarketView();

    /** A stand-in sampler that always wants the same thing, so the policy is what is under test. */
    private record Fixed(Side side, String price, String quantity) implements Strategy {
        @Override
        public Optional<OrderIntent> decide(String symbol, MarketView market,
                                            PortfolioView portfolio, Random rng) {
            return Optional.of(new OrderIntent(side, new BigDecimal(price), new BigDecimal(quantity)));
        }
    }

    private static final Strategy SILENT = (symbol, market, portfolio, rng) -> Optional.empty();

    /** The demo's seeded account: $1,000,000 and 1,000 ARVX. */
    private static PortfolioView seeded(String cash, String shares) {
        return new PortfolioView(Map.of("USD", new BigDecimal(cash)),
                Map.of("ARVX", new BigDecimal(shares)));
    }

    private static PortfolioView seeded() {
        return seeded("1000000", "1000");
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " got " + actual);
    }

    // --- passthrough: in normal operation the policy must be invisible ---

    @Test
    void normalBuyIsUnchanged() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        OrderIntent intent = policy.decide("ARVX", MARKET, seeded(), RNG).orElseThrow();
        assertEquals(Side.BUY, intent.side());
        eq("10", intent.quantity());
    }

    @Test
    void normalSellIsUnchanged() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.SELL, "42.10", "10"));
        OrderIntent intent = policy.decide("ARVX", MARKET, seeded(), RNG).orElseThrow();
        assertEquals(Side.SELL, intent.side());
        eq("10", intent.quantity());
    }

    @Test
    void aSilentDelegateStaysSilent() {
        assertTrue(new LiquidityAwareStrategy(SILENT).decide("ARVX", MARKET, seeded(), RNG).isEmpty());
    }

    // --- fails closed ---

    @Test
    void declinesWithoutHoldingsData() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        assertTrue(policy.decide("ARVX", MARKET, null, RNG).isEmpty(), "null portfolio");
        assertTrue(policy.decide("ARVX", MARKET, PortfolioView.empty(), RNG).isEmpty(), "empty");
    }

    @Test
    void declinesOnUnknownSymbolOrBadPrice() {
        assertTrue(new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"))
                .decide("NOPE", MARKET, seeded(), RNG).isEmpty());
        assertTrue(new LiquidityAwareStrategy(new Fixed(Side.BUY, "0", "10"))
                .decide("ARVX", MARKET, seeded(), RNG).isEmpty());
    }

    // --- buying scaled to available cash ---

    @Test
    void buyIsCappedOnlyWhenCashIsNearlyGone() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG); // baseline 1,000,000 -> floor 250,000

        // Spendable = (300,000 - 250,000) * 0.10 = 5,000 -> 118 shares; 10 still fits.
        eq("10", policy.decide("ARVX", MARKET, seeded("300000", "1000"), RNG).orElseThrow().quantity());

        // Spendable = (250,100 - 250,000) * 0.10 = 10 -> not even one share.
        assertTrue(policy.decide("ARVX", MARKET, seeded("250100", "1000"), RNG).isEmpty());
    }

    @Test
    void buyDeclinesAtTheFloor() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        // Exactly at the floor: nothing spendable, and shares are held so no forced sell either.
        assertTrue(policy.decide("ARVX", MARKET, seeded("250000", "1000"), RNG).isEmpty());
    }

    @Test
    void buyingCanNeverBreachTheFloor() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "100000"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        OrderIntent intent =
                policy.decide("ARVX", MARKET, seeded("1000000", "1000"), RNG).orElseThrow();
        // Spendable = 750,000 * 0.10 = 75,000 -> 75,000/42.10 = 1781 shares.
        eq("1781", intent.quantity());
        BigDecimal cost = intent.quantity().multiply(new BigDecimal("42.10"));
        assertTrue(cost.compareTo(new BigDecimal("750000")) < 0, "must not cross the floor");
    }

    @Test
    void budgetFractionIsConfigurable() {
        LiquidityAwareStrategy policy = new LiquidityAwareStrategy(
                new Fixed(Side.BUY, "42.10", "100000"), BigDecimal.ONE,
                LiquidityAwareStrategy.DEFAULT_CASH_FLOOR_FRACTION);
        policy.decide("ARVX", MARKET, seeded(), RNG);
        // Whole spendable amount: 750,000/42.10 = 17814.
        eq("17814", policy.decide("ARVX", MARKET, seeded("1000000", "1000"), RNG)
                .orElseThrow().quantity());
    }

    // --- selling to maintain liquidity ---

    @Test
    void belowTheFloorForcesASellEvenWhenTheSamplerWantedToBuy() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        OrderIntent intent =
                policy.decide("ARVX", MARKET, seeded("100000", "1000"), RNG).orElseThrow();
        assertEquals(Side.SELL, intent.side(), "must raise cash regardless of what was asked for");
    }

    @Test
    void forcedSellIsBoundedByHoldings() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        // Needs 3,564 shares to restore the floor but holds 1,000 — no shorting.
        eq("1000", policy.decide("ARVX", MARKET, seeded("100000", "1000"), RNG)
                .orElseThrow().quantity());
    }

    @Test
    void forcedSellSellsOnlyWhatIsNeededAndActuallyReachesTheFloor() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        // Shortfall 1,000 / 42.10 = 23.75 -> 24 shares. Rounding DOWN to 23 would raise only 968.30
        // and never restore the floor, so the requirement is rounded up and then clamped.
        OrderIntent intent =
                policy.decide("ARVX", MARKET, seeded("249000", "5000"), RNG).orElseThrow();
        eq("24", intent.quantity());
        BigDecimal raised = intent.quantity().multiply(new BigDecimal("42.10"));
        assertTrue(raised.compareTo(new BigDecimal("1000")) >= 0, "must actually clear the shortfall");
    }

    @Test
    void declinesRatherThanShortingWhenBrokeAndEmpty() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.BUY, "42.10", "10"));
        policy.decide("ARVX", MARKET, seeded(), RNG);
        assertTrue(policy.decide("ARVX", MARKET, seeded("0", "0"), RNG).isEmpty());
    }

    @Test
    void floorFractionIsConfigurable() {
        LiquidityAwareStrategy policy = new LiquidityAwareStrategy(
                new Fixed(Side.BUY, "42.10", "10"),
                LiquidityAwareStrategy.DEFAULT_BUY_BUDGET_FRACTION, new BigDecimal("0.90"));
        policy.decide("ARVX", MARKET, seeded(), RNG); // floor = 900,000
        assertEquals(Side.SELL,
                policy.decide("ARVX", MARKET, seeded("500000", "1000"), RNG).orElseThrow().side());
    }

    // --- no shorting ---

    @Test
    void sellIsClampedToHoldings() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.SELL, "42.10", "10"));
        eq("4", policy.decide("ARVX", MARKET, seeded("1000000", "4"), RNG).orElseThrow().quantity());
    }

    @Test
    void sellDeclinesWithNoHoldings() {
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new Fixed(Side.SELL, "42.10", "10"));
        assertTrue(policy.decide("ARVX", MARKET, seeded("1000000", "0"), RNG).isEmpty());
    }

    // The FX block that lived here is gone: LiquidityAwareStrategy resolves its bounds through
    // InstrumentCatalog, and no FX pair is listed any more (fxc/docs/PROBLEMS.md P23), so the
    // policy now correctly declines every FX symbol. FX lot/tick arithmetic is still covered by
    // fxc-common's InstrumentTest and the exchange's OrderBookTest, which build pairs directly.

    // --- wiring: which strategies get the policy ---

    @Test
    void randoIsNotWrappedAndBookerBookfishAre() {
        assertInstanceOf(SamplingStrategy.class, Strategies.byName("rando"));
        assertFalse(Strategies.byName("rando") instanceof LiquidityAwareStrategy,
                "rando is the naive strategy and must stay bare");
        assertInstanceOf(LiquidityAwareStrategy.class, Strategies.byName("booker"));
        assertInstanceOf(LiquidityAwareStrategy.class, Strategies.byName("bookfish"));
        assertTrue(Strategies.isNaive("rando"));
        assertFalse(Strategies.isNaive("booker"));
    }

    @Test
    void randoStillIgnoresThePortfolioEntirely() {
        // The reason this is a decorator: rando's behaviour must be provably untouched.
        Strategy rando = new SamplingStrategy("rando", new RandoSampler());
        MarketView market = new MarketView();
        market.setLastSale("ARVX", new BigDecimal("42.10"));

        Optional<OrderIntent> withHoldings = rando.decide("ARVX", market, seeded(), new Random(5));
        Optional<OrderIntent> without = rando.decide("ARVX", market, PortfolioView.empty(), new Random(5));
        assertEquals(withHoldings, without);
    }

    @Test
    void anUnchangedQuantityReturnsTheSameIntentInstance() {
        // Cheap proof the policy is a no-op in the common case rather than reallocating per decision.
        Fixed delegate = new Fixed(Side.BUY, "42.10", "10");
        Optional<OrderIntent> raw = delegate.decide("ARVX", MARKET, seeded(), RNG);
        Optional<OrderIntent> viaPolicy =
                new LiquidityAwareStrategy(delegate).decide("ARVX", MARKET, seeded(), RNG);
        assertEquals(raw.orElseThrow(), viaPolicy.orElseThrow());
    }

    // --- the acceptance property ---

    @Test
    void aLongRunNeverAsksForSomethingTheBrokerWouldReject() {
        // Simulate holdings drifting as orders fill; every emitted intent must be fundable and
        // covered, i.e. would not be rejected.
        LiquidityAwareStrategy policy =
                new LiquidityAwareStrategy(new SamplingStrategy("booker", new BookerSampler()));
        MarketView market = new MarketView();
        market.setLastSale("ARVX", new BigDecimal("42.10"));
        Random rng = new Random(4);
        BigDecimal cash = new BigDecimal("1000000");
        BigDecimal shares = new BigDecimal("1000");
        int emitted = 0;

        for (int i = 0; i < 400; i++) {
            PortfolioView portfolio =
                    new PortfolioView(Map.of("USD", cash), Map.of("ARVX", shares));
            Optional<OrderIntent> decision = policy.decide("ARVX", market, portfolio, rng);
            if (decision.isEmpty()) {
                continue;
            }
            emitted++;
            OrderIntent intent = decision.get();
            BigDecimal notional = intent.price().multiply(intent.quantity());
            if (intent.side() == Side.BUY) {
                assertTrue(notional.compareTo(cash) <= 0, "a buy must be fundable");
                cash = cash.subtract(notional);
                shares = shares.add(intent.quantity());
            } else {
                assertTrue(intent.quantity().compareTo(shares) <= 0, "no shorting");
                cash = cash.add(notional);
                shares = shares.subtract(intent.quantity());
            }
            assertTrue(cash.signum() >= 0, "cash must never go negative");
            assertTrue(shares.signum() >= 0, "shares must never go negative");
        }

        assertTrue(emitted > 200, "the policy should keep trading, not shut down (was " + emitted + ")");
        assertTrue(cash.signum() > 0, "still solvent — the naive version would not be");
    }
}
