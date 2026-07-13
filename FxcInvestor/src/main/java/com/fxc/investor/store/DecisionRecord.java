package com.fxc.investor.store;

import java.math.BigDecimal;

/**
 * One row of the FxcInvestor decision log (docs/DESIGN.md §4.4): what the agent decided on a tick
 * and the broker's response. {@code side}/{@code quantity}/{@code price}/{@code clOrdId} are null
 * when the strategy declined to trade ({@code status = SKIPPED}).
 *
 * @param createdAt epoch millis
 * @param account   account number
 * @param symbol    instrument symbol
 * @param strategy  strategy name (rando/booker/bookfish)
 * @param side      BUY/SELL, or null
 * @param quantity  order quantity, or null
 * @param price     limit price submitted, or null
 * @param clOrdId   client order id, or null
 * @param status    broker order status, or {@code SKIPPED}
 */
public record DecisionRecord(
        long createdAt,
        String account,
        String symbol,
        String strategy,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        String clOrdId,
        String status) {

    public static final String SKIPPED = "SKIPPED";
}
