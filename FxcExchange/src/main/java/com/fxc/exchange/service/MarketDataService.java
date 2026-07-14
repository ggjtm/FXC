package com.fxc.exchange.service;

import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.book.Trade;
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

    /** A subscription and the FIX {@code MarketDepth(264)} it requested (levels per side). */
    private record Subscription(Object target, String mdReqId, int depth) {
    }

    private final MatchingEngine engine;
    private final MarketDataPublisher publisher;
    private final Map<String, CopyOnWriteArrayList<Subscription>> bySymbol = new ConcurrentHashMap<>();
    /** Last traded price/size per symbol, echoed into snapshots as an MDEntryType=TRADE entry. */
    private final Map<String, OrderBook.Level> lastSale = new ConcurrentHashMap<>();

    public MarketDataService(MatchingEngine engine, MarketDataPublisher publisher) {
        this.engine = engine;
        this.publisher = publisher;
    }

    /** Default depth (levels per side) when a request does not set MarketDepth. */
    private static final int DEFAULT_DEPTH = 10;

    /** Depth tiers from FxcExchange/docs/stories/001, mapped to FIX {@code MarketDepth(264)}. */
    public static final int TOP_OF_BOOK = 1;
    public static final int MARKET_DEPTH = 5;
    public static final int FULL_DEPTH = 0; // 0 = full book

    /** Register a subscription at the default depth (back-compat). */
    public void subscribe(Object target, String mdReqId, List<String> symbols) {
        subscribe(target, mdReqId, symbols, DEFAULT_DEPTH);
    }

    /**
     * Register a subscription for each symbol at the requested depth (levels per side; {@code 0} =
     * full book) and send an immediate snapshot (quotes + last sale).
     */
    public void subscribe(Object target, String mdReqId, List<String> symbols, int depth) {
        for (String symbol : symbols) {
            bySymbol.computeIfAbsent(symbol, s -> new CopyOnWriteArrayList<>())
                    .add(new Subscription(target, mdReqId, depth));
            publisher.publishSnapshot(target, mdReqId, symbol,
                    bids(symbol, depth), asks(symbol, depth), lastSale.get(symbol));
        }
    }

    /** Send a fresh snapshot to every subscriber of a symbol (e.g. after book changes). */
    public void publishSnapshot(String symbol) {
        List<Subscription> subs = bySymbol.get(symbol);
        if (subs == null) {
            return;
        }
        for (Subscription sub : subs) {
            publisher.publishSnapshot(sub.target(), sub.mdReqId(), symbol,
                    bids(symbol, sub.depth()), asks(symbol, sub.depth()), lastSale.get(symbol));
        }
    }

    private int levels(int depth) {
        return depth <= 0 ? Integer.MAX_VALUE : depth;
    }

    private List<OrderBook.Level> bids(String symbol, int depth) {
        return engine.book(symbol).map(b -> b.bidLevels(levels(depth))).orElseGet(List::of);
    }

    private List<OrderBook.Level> asks(String symbol, int depth) {
        return engine.book(symbol).map(b -> b.askLevels(levels(depth))).orElseGet(List::of);
    }

    @Override
    public void onEvent(ExchangeEvent event) {
        // Track last sale even if nobody is subscribed yet, so a later subscriber's first snapshot
        // carries it.
        if (!event.trades().isEmpty()) {
            Trade last = event.trades().get(event.trades().size() - 1);
            lastSale.put(event.symbol(), new OrderBook.Level(last.price(), last.quantity()));
        }
        List<Subscription> subs = bySymbol.get(event.symbol());
        if (subs == null || subs.isEmpty()) {
            return;
        }
        OrderBook.Level bid = bestBid(event.symbol());
        OrderBook.Level ask = bestAsk(event.symbol());
        for (Subscription sub : subs) {
            publisher.publishIncremental(sub.target(), sub.mdReqId(), event.symbol(), bid, ask, event.trades());
        }
        // Also refresh the depth snapshot so subscribers (e.g. the broker's book cache) stay current.
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
