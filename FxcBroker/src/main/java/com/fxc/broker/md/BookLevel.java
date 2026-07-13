package com.fxc.broker.md;

import java.math.BigDecimal;

/**
 * One aggregated order-book level cached by the broker (FxcBroker/docs/stories/001).
 *
 * @param side  {@code BID} or {@code OFFER}
 * @param price level price
 * @param size  aggregated resting quantity at that price
 */
public record BookLevel(String side, BigDecimal price, BigDecimal size) {

    public static final String BID = "BID";
    public static final String OFFER = "OFFER";
}
