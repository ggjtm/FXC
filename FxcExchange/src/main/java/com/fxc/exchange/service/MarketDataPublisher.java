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

    /**
     * Publish a full snapshot carrying up to N bid and ask levels (depth), best first, plus the last
     * sale ({@code lastSale}, nullable) as an {@code MDEntryType=TRADE} entry — so a subscriber gets
     * top-of-book/market-depth/full-depth quotes and the last traded price together
     * (FxcExchange/docs/stories/001, tiers 1–3).
     */
    void publishSnapshot(Object target, String mdReqId, String symbol,
                         List<OrderBook.Level> bids, List<OrderBook.Level> asks,
                         OrderBook.Level lastSale);

    void publishIncremental(Object target, String mdReqId, String symbol,
                            OrderBook.Level bid, OrderBook.Level ask, List<Trade> trades);
}
