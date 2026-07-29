package com.fxc.broker.model;

import java.math.BigDecimal;

/**
 * A fill received from the exchange for a client order (docs/DESIGN.md §4.2).
 *
 * <p>{@code account} and {@code ts} were added for the broker console's per-account P&amp;L series
 * (docs/DESIGN.md §6): without them a fill cannot be attributed to an account or placed in time
 * except by joining {@code CLIENT_ORDER}, which the archiver deletes — so a session's P&amp;L history
 * was not derivable from stored data at all.
 *
 * @param execId        exchange ExecID(17)
 * @param clientOrderId the owning client order (ClOrdID)
 * @param account       the account the owning order belongs to
 * @param symbol        instrument symbol
 * @param side          buy or sell
 * @param lastQty       quantity of this fill
 * @param lastPx        price of this fill
 * @param cumQty        cumulative filled quantity after this fill
 * @param status        order status conveyed with this report
 * @param ts            when the fill was applied (epoch millis)
 */
public record Execution(
        String execId,
        String clientOrderId,
        String account,
        String symbol,
        Side side,
        BigDecimal lastQty,
        BigDecimal lastPx,
        BigDecimal cumQty,
        OrderStatus status,
        long ts) {
}
