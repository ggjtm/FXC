package com.fxc.exchange.feed;

import java.math.BigDecimal;

/**
 * One executed trade reduced to the three fields the feed/candle service aggregates on
 * (FxcExchange/docs/stories/001): its execution timestamp, price, and quantity. Sourced from the
 * GridGain {@code TRADE} hot table and the MariaDB {@code TRADE_ARCHIVE} cold table.
 *
 * @param ts    execution time, epoch millis
 * @param price execution price
 * @param qty   executed quantity
 */
public record TradePoint(long ts, BigDecimal price, BigDecimal qty) {
}
