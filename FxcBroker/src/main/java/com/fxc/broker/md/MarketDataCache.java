package com.fxc.broker.md;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Caches the latest order-book depth and last trade price per instrument, fed from FxcExchange's FIX
 * market-data feed (FxcBroker/docs/stories/001). The OFX book-snapshot handler reads from here so an
 * investor request needs no per-call FIX round-trip.
 */
public final class MarketDataCache {

    private final ConcurrentHashMap<String, List<BookLevel>> books = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> lastPrice = new ConcurrentHashMap<>();

    public void setBook(String symbol, List<BookLevel> levels) {
        books.put(symbol, List.copyOf(levels));
    }

    public void setLastPrice(String symbol, BigDecimal price) {
        lastPrice.put(symbol, price);
    }

    public List<BookLevel> book(String symbol) {
        return books.getOrDefault(symbol, List.of());
    }

    /** Top-{@code depth} levels per side for a symbol, bids then asks (best first). */
    public List<BookLevel> topOfBook(String symbol, int depth) {
        List<BookLevel> all = book(symbol);
        Stream<BookLevel> bids = all.stream().filter(l -> BookLevel.BID.equals(l.side())).limit(depth);
        Stream<BookLevel> asks = all.stream().filter(l -> BookLevel.OFFER.equals(l.side())).limit(depth);
        return Stream.concat(bids, asks).toList();
    }

    public Optional<BigDecimal> lastPrice(String symbol) {
        return Optional.ofNullable(lastPrice.get(symbol));
    }
}
