package com.fxc.exchange.service;

import com.fxc.exchange.book.Trade;
import java.util.List;

/**
 * Emitted by {@link MatchingEngineService} after each accepted order or cancel, so downstream
 * services (market data, clearing, live feed) can react. {@code trades} is empty when an order
 * rested or was cancelled without crossing. {@code ts} is the event's epoch-millis timestamp — the
 * same value stamped on any persisted trades, so the live feed and candle service share one clock.
 */
public record ExchangeEvent(String symbol, List<Trade> trades, long ts) {
}
