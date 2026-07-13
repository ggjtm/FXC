package com.fxc.broker.model;

import java.math.BigDecimal;

/**
 * A fill received from the exchange for a client order (docs/DESIGN.md §4.2).
 *
 * @param execId        exchange ExecID(17)
 * @param clientOrderId the owning client order (ClOrdID)
 * @param symbol        instrument symbol
 * @param side          buy or sell
 * @param lastQty       quantity of this fill
 * @param lastPx        price of this fill
 * @param cumQty        cumulative filled quantity after this fill
 * @param status        order status conveyed with this report
 */
public record Execution(
        String execId,
        String clientOrderId,
        String symbol,
        Side side,
        BigDecimal lastQty,
        BigDecimal lastPx,
        BigDecimal cumQty,
        OrderStatus status) {
}
