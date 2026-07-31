package com.fxc.broker.pnl;

/**
 * How much P&amp;L history the console gets, and at what cost (docs/stories/003).
 *
 * <p>Three bounds rather than one, because they answer different questions and the previous single
 * point ceiling answered none of them well — it bounded heap, let the payload grow until it was hit,
 * and then froze the curve (docs/PROBLEMS.md P17):
 *
 * <ul>
 *   <li>{@code windowMs} — what the chart <em>means</em>: the last quarter of an hour of trading.</li>
 *   <li>{@code maxPointsPerAccount} — what a response <em>costs</em>. The console polls once a second,
 *       so this is the difference between a steady 200 KB and an unbounded megabyte.</li>
 *   <li>{@code groupSize} — how many accounts each of the three groups carries: the best by P&amp;L,
 *       the most active, and the worst by P&amp;L. A single "busiest N" answered only one of the three
 *       questions this chart is read for; accounts outside the groups still report their totals.</li>
 * </ul>
 *
 * <p>{@code maxRetainedPoints} is the backstop: if a fill rate ever puts more than that inside the
 * window, the oldest are dropped anyway. Still rolling, never frozen.
 *
 * <p>{@code internalAccountsBelow} hides the broker's own accounts — the issuer and the market makers,
 * numbered below 100 — from the console entirely. They are infrastructure, and showing them ruins the
 * view: the market makers hold the whole float, so mark-to-market on it dwarfs every customer's P&amp;L
 * and they take every group by three orders of magnitude.
 */
public record PnlSettings(long windowMs, int maxPointsPerAccount, int groupSize,
                          int maxRetainedPoints, long internalAccountsBelow) {

    public static PnlSettings defaults() {
        return new PnlSettings(PnlService.DEFAULT_WINDOW_MS, PnlService.DEFAULT_MAX_POINTS_PER_ACCOUNT,
                PnlService.DEFAULT_GROUP_SIZE, PnlService.DEFAULT_MAX_RETAINED_POINTS,
                PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
    }
}
