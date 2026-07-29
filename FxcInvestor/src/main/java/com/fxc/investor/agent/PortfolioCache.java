package com.fxc.investor.agent;

import com.fxc.investor.strategy.PortfolioView;
import java.util.function.LongSupplier;

/**
 * The agent's view of its own holdings, refreshed on an interval rather than per decision.
 *
 * <p><b>Why this exists.</b> Both agent loops used to pass {@code PortfolioView.empty()}, so no
 * strategy could see its own cash or shares. A liquidity-managed strategy needs them — it has to size
 * a buy against available cash and sell to restore a cash floor.
 *
 * <p><b>Why an interval and not per tick.</b> Reading holdings costs an OFX statement round trip, and
 * FxcBroker's OFX server is a fixed pool of four threads. Fetching per decision would double the
 * request load against that ceiling for data that changes slowly. The refresh interval is
 * configurable ({@code agent.portfolioRefreshMs}); between refreshes the last known view is served.
 *
 * <p><b>Failure behaviour.</b> A failed fetch keeps the previous view and waits a full interval before
 * retrying, so a broker outage degrades to stale-but-usable data instead of a hot retry loop. Before
 * the first successful fetch the view is empty — which makes a liquidity-managed strategy decline to
 * trade rather than guess, the safe direction.
 *
 * <p>Not thread-safe for concurrent refreshes, but safe for one refreshing loop plus readers: the
 * fields are {@code volatile} and replaced wholesale, never mutated in place.
 */
public final class PortfolioCache {

    private final PortfolioSource source;
    private final String account;
    private final long refreshIntervalMs;
    private final LongSupplier clock;

    private volatile PortfolioView current = PortfolioView.empty();
    /** A refresh has been *attempted* — this is what paces retries. */
    private volatile boolean everAttempted;
    /** A refresh has *succeeded* — this is what tells callers the view is real. */
    private volatile boolean everFetched;
    private volatile long lastAttemptMs;
    private volatile int refreshCount;
    private volatile int failureCount;

    public PortfolioCache(PortfolioSource source, String account, long refreshIntervalMs) {
        this(source, account, refreshIntervalMs, System::currentTimeMillis);
    }

    /** Test/DI constructor with an injectable clock (epoch millis). */
    public PortfolioCache(PortfolioSource source, String account, long refreshIntervalMs,
                          LongSupplier clock) {
        this.source = source;
        this.account = account;
        this.refreshIntervalMs = refreshIntervalMs;
        this.clock = clock;
    }

    /**
     * The current holdings, refreshing first if the cached view has aged past the interval.
     *
     * <p>Never throws: a failed refresh returns the last known view (empty if there has never been a
     * successful one), because a decision loop must not die because a statement read failed.
     */
    public PortfolioView current() {
        long now = clock.getAsLong();
        // Paced on the last *attempt*, not the last success: keying this on success would make a down
        // broker retry on every single decision, which is the retry storm the interval exists to stop.
        if (!everAttempted || now - lastAttemptMs >= refreshIntervalMs) {
            refresh();
        }
        return current;
    }

    /** Force a refresh regardless of age. @return true if it succeeded */
    public boolean refresh() {
        lastAttemptMs = clock.getAsLong();
        everAttempted = true;
        try {
            PortfolioView fetched = source.fetch(account);
            if (fetched != null) {
                current = fetched;
                everFetched = true;
                refreshCount++;
                return true;
            }
            failureCount++;
            return false;
        } catch (Exception e) {
            // Best-effort: keep serving the previous view. Reported rather than silent, but not fatal.
            failureCount++;
            System.err.println("portfolio refresh failed for " + account + ": " + e.getMessage());
            return false;
        }
    }

    /** Whether a successful fetch has ever happened — false means {@link #current()} is still empty. */
    public boolean hasData() {
        return everFetched;
    }

    /** Successful refreshes so far (diagnostics; also how tests prove this is not per-tick). */
    public int refreshCount() {
        return refreshCount;
    }

    /** Failed refresh attempts so far. */
    public int failureCount() {
        return failureCount;
    }
}
