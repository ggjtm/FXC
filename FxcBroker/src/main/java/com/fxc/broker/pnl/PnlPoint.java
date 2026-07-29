package com.fxc.broker.pnl;

import java.math.BigDecimal;

/**
 * One point on an account's session P&amp;L curve (docs/DESIGN.md §6): the broker console plots
 * {@link #tradeCount()} on the x axis against {@link #relative()} on the y axis.
 *
 * <p>{@code relative = realized + unrealized} by construction, so the two components always account
 * for the whole move.
 *
 * @param tradeCount fills applied to this account so far (the x axis)
 * @param ts         when this point was sampled (epoch millis)
 * @param realized   cumulative closed-trade P&amp;L in USD
 * @param unrealized mark-to-market P&amp;L still open, in USD
 * @param relative   equity now minus equity at session start, in USD (the y axis)
 */
public record PnlPoint(int tradeCount, long ts, BigDecimal realized, BigDecimal unrealized,
                       BigDecimal relative) {
}
