package com.fxc.broker.oms;

import java.math.BigDecimal;

/**
 * Forwards fills to FxcPub as FIX drop-copies (docs/DESIGN.md §4.2/§4.3). Implemented by the
 * drop-copy FIX initiator; a no-op default is used when FxcPub is not configured (e.g. it is down).
 */
@FunctionalInterface
public interface DropCopyPublisher {

    void publishFill(String clOrdId, String symbol, String side, BigDecimal lastQty, BigDecimal lastPx);

    /** Publisher that drops fills silently (FxcPub not wired). */
    DropCopyPublisher NONE = (clOrdId, symbol, side, lastQty, lastPx) -> { };
}
