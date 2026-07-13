package com.fxc.investor.strategy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The market signal a strategy sees (docs/DESIGN.md §4.4). Populated by the agent from the FxcPub
 * feed and/or OFX statements. Carries the last-sale price per instrument, a traded-volume histogram
 * (for {@code bookfish}), and an optional order-book snapshot (for {@code booker}).
 *
 * <p>{@code rando} uses only {@link #lastSale}; {@code booker}/{@code bookfish} additionally use the
 * book/volume data (see {@code docs/stories/}).
 */
public final class MarketView {

    private final Map<String, BigDecimal> lastSale = new ConcurrentHashMap<>();
    // symbol -> (price -> cumulative traded quantity) for bookfish
    private final Map<String, Map<BigDecimal, BigDecimal>> tradedVolume = new ConcurrentHashMap<>();
    // symbol -> book levels (price, resting qty) for booker
    private final Map<String, List<Level>> book = new ConcurrentHashMap<>();

    /** One aggregated price level (used for the booker order-book histogram). */
    public record Level(BigDecimal price, BigDecimal quantity) {
    }

    public Optional<BigDecimal> lastSale(String symbol) {
        return Optional.ofNullable(lastSale.get(symbol));
    }

    public void setLastSale(String symbol, BigDecimal price) {
        lastSale.put(symbol, price);
    }

    /** Record a trade into both the last-sale and the traded-volume histogram. */
    public void recordTrade(String symbol, BigDecimal price, BigDecimal quantity) {
        lastSale.put(symbol, price);
        tradedVolume.computeIfAbsent(symbol, s -> new ConcurrentHashMap<>())
                .merge(price, quantity, BigDecimal::add);
    }

    /** Traded-volume histogram (price -> cumulative quantity) for a symbol. */
    public Map<BigDecimal, BigDecimal> tradedVolume(String symbol) {
        return tradedVolume.getOrDefault(symbol, Map.of());
    }

    public void setBook(String symbol, List<Level> levels) {
        book.put(symbol, List.copyOf(levels));
    }

    public List<Level> book(String symbol) {
        return book.getOrDefault(symbol, List.of());
    }
}
