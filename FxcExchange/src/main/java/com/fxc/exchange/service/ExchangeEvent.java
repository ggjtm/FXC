package com.fxc.exchange.service;

import com.fxc.exchange.book.Trade;
import java.util.List;

/**
 * Emitted by {@link MatchingEngineService} after each accepted order or cancel, so downstream
 * services (market data, clearing) can react. {@code trades} is empty when an order rested or was
 * cancelled without crossing.
 */
public record ExchangeEvent(String symbol, List<Trade> trades) {
}
