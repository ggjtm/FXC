package com.fxc.exchange.book;

import java.util.List;

/**
 * Outcome of submitting an order: either accepted (with any trades it generated) or rejected
 * (with a reason). The {@code order} carries the resulting status/cumQty.
 */
public record MatchResult(Order order, List<Trade> trades, boolean accepted, String rejectReason) {

    public static MatchResult accepted(Order order, List<Trade> trades) {
        return new MatchResult(order, trades, true, null);
    }

    public static MatchResult rejected(Order order, String reason) {
        return new MatchResult(order, List.of(), false, reason);
    }
}
