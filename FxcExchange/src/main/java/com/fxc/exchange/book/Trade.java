package com.fxc.exchange.book;

import java.math.BigDecimal;

/**
 * An executed trade between a resting order and an incoming (aggressing) order. Carries both sides
 * so the FIX layer can emit an {@code ExecutionReport} to each broker and the clearing layer can
 * build settlement obligations for both.
 *
 * @param tradeId       unique trade id
 * @param symbol        instrument symbol
 * @param price         execution price (always the resting order's price — price improvement to taker)
 * @param quantity      executed quantity
 * @param buyOrderId    order id on the buy side
 * @param sellOrderId   order id on the sell side
 * @param buyBroker     broker on the buy side
 * @param sellBroker    broker on the sell side
 * @param aggressorSide the side of the incoming order that crossed the spread
 * @param sequence      engine sequence number (monotonic, for ordering/audit)
 */
public record Trade(
        String tradeId,
        String symbol,
        BigDecimal price,
        BigDecimal quantity,
        String buyOrderId,
        String sellOrderId,
        String buyBroker,
        String sellBroker,
        Side aggressorSide,
        long sequence) {

    /** The order id on the given side. */
    public String orderIdFor(Side side) {
        return side == Side.BUY ? buyOrderId : sellOrderId;
    }

    /** The broker on the given side. */
    public String brokerFor(Side side) {
        return side == Side.BUY ? buyBroker : sellBroker;
    }
}
