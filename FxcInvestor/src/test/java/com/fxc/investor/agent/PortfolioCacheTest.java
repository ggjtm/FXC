package com.fxc.investor.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.investor.strategy.PortfolioView;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The holdings cache behind the agent loops.
 *
 * <p>The behaviour under test is mostly about *not* doing work: a statement read is an OFX round trip
 * against a four-thread server, so the interval must actually suppress fetches, and a failure must
 * degrade to stale data rather than a retry storm.
 */
class PortfolioCacheTest {

    private static PortfolioView view(String cash, String shares) {
        return new PortfolioView(
                Map.of("USD", new BigDecimal(cash)), Map.of("ARVX", new BigDecimal(shares)));
    }

    /** A source that counts calls and can be told to fail. */
    private static final class FakeSource implements PortfolioSource {
        int calls;
        boolean fail;
        PortfolioView next = view("1000000", "1000");
        PortfolioView returnNull;

        @Override
        public PortfolioView fetch(String account) throws Exception {
            calls++;
            if (fail) {
                throw new IllegalStateException("broker unreachable");
            }
            return returnNull != null ? null : next;
        }
    }

    @Test
    void fetchesOnFirstUse() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> now[0]);

        assertFalse(cache.hasData());
        PortfolioView first = cache.current();

        assertEquals(1, source.calls);
        assertTrue(cache.hasData());
        assertEquals(new BigDecimal("1000000"), first.cash("USD"));
        assertEquals(new BigDecimal("1000"), first.shares("ARVX"));
    }

    @Test
    void servesTheCachedViewWithinTheInterval() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> now[0]);

        cache.current();
        // Many decisions inside one interval must cost exactly one statement read.
        for (int i = 0; i < 50; i++) {
            now[0] += 90;
            cache.current();
        }
        assertEquals(1, source.calls, "the interval must suppress per-tick fetches");
    }

    @Test
    void refreshesOncePastTheInterval() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> now[0]);

        cache.current();
        now[0] += 5_000;
        cache.current();
        assertEquals(2, source.calls);

        now[0] += 4_999;
        cache.current();
        assertEquals(2, source.calls, "still inside the second interval");

        now[0] += 1;
        cache.current();
        assertEquals(3, source.calls);
    }

    @Test
    void picksUpChangedHoldings() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 1_000, () -> now[0]);

        assertEquals(new BigDecimal("1000"), cache.current().shares("ARVX"));
        source.next = view("958000", "1010");
        now[0] += 1_000;
        assertEquals(new BigDecimal("1010"), cache.current().shares("ARVX"));
        assertEquals(new BigDecimal("958000"), cache.current().cash("USD"));
    }

    @Test
    void keepsTheLastGoodViewWhenARefreshFails() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 1_000, () -> now[0]);

        PortfolioView good = cache.current();
        assertEquals(new BigDecimal("1000"), good.shares("ARVX"));

        source.fail = true;
        now[0] += 1_000;
        PortfolioView afterFailure = cache.current();

        // Stale but usable — a decision loop must not die because a statement read failed.
        assertEquals(new BigDecimal("1000"), afterFailure.shares("ARVX"));
        assertEquals(1, cache.failureCount());
        assertTrue(cache.hasData());
    }

    @Test
    void doesNotHammerAFailingBrokerWithinTheInterval() {
        FakeSource source = new FakeSource();
        source.fail = true;
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> now[0]);

        cache.current();
        int afterFirst = source.calls;
        for (int i = 0; i < 20; i++) {
            now[0] += 100;
            cache.current();
        }
        assertEquals(afterFirst, source.calls, "a failed attempt still waits a full interval");
    }

    @Test
    void beforeAnySuccessTheViewIsEmptySoStrategiesFailClosed() {
        FakeSource source = new FakeSource();
        source.fail = true;
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> 1_000L);

        PortfolioView view = cache.current();
        assertTrue(view.isEmpty(), "no data yet — a liquidity-managed strategy must decline, not guess");
        assertEquals(BigDecimal.ZERO, view.cash("USD"));
        assertEquals(BigDecimal.ZERO, view.shares("ARVX"));
        assertFalse(cache.hasData());
    }

    @Test
    void recoversAfterATransientFailure() {
        FakeSource source = new FakeSource();
        source.fail = true;
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 1_000, () -> now[0]);

        cache.current();
        assertFalse(cache.hasData());

        source.fail = false;
        now[0] += 1_000;
        assertEquals(new BigDecimal("1000"), cache.current().shares("ARVX"));
        assertTrue(cache.hasData());
        assertEquals(1, cache.refreshCount());
    }

    @Test
    void aNullFetchCountsAsAFailureRatherThanWipingTheView() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 1_000, () -> now[0]);

        cache.current();
        source.returnNull = PortfolioView.empty(); // flag: make fetch() return null
        now[0] += 1_000;

        assertEquals(new BigDecimal("1000"), cache.current().shares("ARVX"), "keeps the good view");
        assertEquals(1, cache.failureCount());
    }

    @Test
    void forcedRefreshIgnoresTheInterval() {
        FakeSource source = new FakeSource();
        PortfolioCache cache = new PortfolioCache(source, "A", 60_000, () -> 1_000L);

        cache.current();
        assertTrue(cache.refresh());
        assertTrue(cache.refresh());
        assertEquals(3, source.calls);
        assertEquals(3, cache.refreshCount());
    }

    @Test
    void passesTheAccountThrough() {
        String[] seen = {null};
        PortfolioCache cache = new PortfolioCache(account -> {
            seen[0] = account;
            return PortfolioView.empty();
        }, "000654321", 1_000, () -> 0L);

        cache.current();
        assertEquals("000654321", seen[0]);
    }

    @Test
    void theViewInstanceIsStableBetweenRefreshes() {
        FakeSource source = new FakeSource();
        long[] now = {1_000L};
        PortfolioCache cache = new PortfolioCache(source, "A", 5_000, () -> now[0]);

        PortfolioView first = cache.current();
        now[0] += 10;
        assertSame(first, cache.current(), "no reallocation per read inside the interval");
    }
}
