package com.fxc.exchange.book;

import java.math.BigDecimal;

/**
 * An inbound order request (before validation and sequencing). Built by the FIX layer from a
 * {@code NewOrderSingle}.
 *
 * @param orderId broker-supplied client order id (ClOrdID)
 * @param broker  owning broker / FIX session id
 * @param symbol  instrument symbol
 * @param side    buy or sell
 * @param type    limit or market
 * @param price   limit price, or {@code null} for a market order
 * @param quantity order quantity
 */
public record NewOrder(
        String orderId,
        String broker,
        String symbol,
        Side side,
        OrderType type,
        BigDecimal price,
        BigDecimal quantity) {
}
