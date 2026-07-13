package com.fxc.investor.agent;

import com.fxc.investor.strategy.OrderIntent;
import java.math.BigDecimal;

/**
 * Record of an order the agent submitted this tick.
 *
 * @param clOrdId       client order id sent to the broker
 * @param intent        the strategy's raw decision
 * @param snappedPrice  the tick-snapped limit price actually submitted
 * @param status        the broker's returned order status
 */
public record SubmittedOrder(String clOrdId, OrderIntent intent, BigDecimal snappedPrice, String status) {
}
