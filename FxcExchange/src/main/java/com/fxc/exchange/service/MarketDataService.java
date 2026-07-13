package com.fxc.exchange.service;

import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.book.OrderBook;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publishes top-of-book and trades to subscribed FIX sessions (docs/DESIGN.md §4.1). On
 * subscription it sends a snapshot; on each {@link ExchangeEvent} it sends an incremental refresh
 * to the symbol's subscribers. Listens to {@link MatchingEngineService} via {@link ExchangeListener}.
 */
public final class MarketDataService implements ExchangeListener {

    private record Subscription(Object target, String mdReqId) {
    }

    private final MatchingEngine engine;
    private final MarketDataPublisher publisher;
    private final Map<String, CopyOnWriteArrayList<Subscription>> bySymbol = new ConcurrentHashMap<>();

    public MarketDataService(MatchingEngine engine, MarketDataPublisher publisher) {
        this.engine = engine;
        this.publisher = publisher;
    }

    /** Depth (levels per side) published in a full snapshot. */
    private static final int SNAPSHOT_DEPTH = 10;

    /** Register a subscription for each symbol and send an immediate depth snapshot. */
    public void subscribe(Object target, String mdReqId, List<String> symbols) {
        for (String symbol : symbols) {
            bySymbol.computeIfAbsent(symbol, s -> new CopyOnWriteArrayList<>())
                    .add(new Subscription(target, mdReqId));
            publisher.publishSnapshot(target, mdReqId, symbol, bids(symbol), asks(symbol));
        }
    }

    /** Send a fresh depth snapshot to every subscriber of a symbol (e.g. after book changes). */
    public void publishSnapshot(String symbol) {
        List<Subscription> subs = bySymbol.get(symbol);
        if (subs == null) {
            return;
        }
        for (Subscription sub : subs) {
            publisher.publishSnapshot(sub.target(), sub.mdReqId(), symbol, bids(symbol), asks(symbol));
        }
    }

    private List<OrderBook.Level> bids(String symbol) {
        return engine.book(symbol).map(b -> b.bidLevels(SNAPSHOT_DEPTH)).orElseGet(List::of);
    }

    private List<OrderBook.Level> asks(String symbol) {
        return engine.book(symbol).map(b -> b.askLevels(SNAPSHOT_DEPTH)).orElseGet(List::of);
    }

    @Override
    public void onEvent(ExchangeEvent event) {
        List<Subscription> subs = bySymbol.get(event.symbol());
        if (subs == null || subs.isEmpty()) {
            return;
        }
        OrderBook.Level bid = bestBid(event.symbol());
        OrderBook.Level ask = bestAsk(event.symbol());
        for (Subscription sub : subs) {
            publisher.publishIncremental(sub.target(), sub.mdReqId(), event.symbol(), bid, ask, event.trades());
        }
        // Also refresh the full depth snapshot so subscribers (e.g. the broker's book cache) stay
        // current as the book changes.
        publishSnapshot(event.symbol());
    }

    private OrderBook.Level bestBid(String symbol) {
        return engine.book(symbol).map(b -> firstOrNull(b.bidLevels(1))).orElse(null);
    }

    private OrderBook.Level bestAsk(String symbol) {
        return engine.book(symbol).map(b -> firstOrNull(b.askLevels(1))).orElse(null);
    }

    private static OrderBook.Level firstOrNull(List<OrderBook.Level> levels) {
        return levels.isEmpty() ? null : levels.get(0);
    }
}
