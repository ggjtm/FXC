package com.fxc.exchange.service;

import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.book.Trade;
import java.util.List;

/**
 * Transport-neutral sink for market data. Implemented by the FIX layer, which turns these calls
 * into {@code MarketDataSnapshotFullRefresh(W)} / {@code MarketDataIncrementalRefresh(X)} messages.
 * {@code bid}/{@code ask} may be {@code null} when that side of the book is empty.
 */
public interface MarketDataPublisher {

    void publishSnapshot(Object target, String mdReqId, String symbol,
                         OrderBook.Level bid, OrderBook.Level ask);

    void publishIncremental(Object target, String mdReqId, String symbol,
                            OrderBook.Level bid, OrderBook.Level ask, List<Trade> trades);
}
