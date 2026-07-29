package com.fxc.exchange.control;

import com.fxc.common.instrument.Instrument;
import com.fxc.exchange.book.Order;
import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.book.TradingSession;
import com.fxc.exchange.book.TradingSession.State;
import com.fxc.exchange.service.MarketDataService;
import com.fxc.exchange.service.MatchingEngineService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The operations behind the exchange console's controls and status pane (docs/DESIGN.md §6):
 * halt/resume trading (market-wide or per symbol), clear the order book, and read a live status
 * snapshot. Pure orchestration over the existing services — it owns no state beyond its start time,
 * and renders no JSON (that stays in the HTTP layer, as with the candle endpoints).
 *
 * <p>Clearing the book mass-cancels live orders, so this class is also responsible for reporting
 * every cancellation to its owning broker through a {@link CancelReporter}. Callers must not reach
 * past it to {@code MatchingEngineService.clearBook}, or brokers' OMS state will silently drift.
 */
public final class ExchangeControlService {

    /** Per-symbol status for the console's status pane and symbol picker. */
    public record SymbolStatus(String symbol, State state, BigDecimal bestBid, BigDecimal bestAsk,
                               BigDecimal lastPrice, int restingOrders) {
    }

    /** A whole-exchange status snapshot. */
    public record ExchangeStatus(State marketState, long uptimeMs, int wsClients, double tradesPerSec,
                                 long totalTrades, List<SymbolStatus> symbols) {
    }

    /** Aggregated book depth for one symbol. */
    public record BookSnapshot(String symbol, List<OrderBook.Level> bids, List<OrderBook.Level> asks) {
    }

    /** Outcome of a control action, so the caller can echo the resulting state back to the UI. */
    public record ControlResult(State marketState, String symbol, int cancelled) {
    }

    private final MatchingEngineService matching;
    private final MarketDataService marketData;
    private final CancelReporter cancelReporter;
    private final LongSupplier clock;
    private final long startedAt;

    private IntSupplier wsClients = () -> 0;
    private DoubleSupplier tradesPerSec = () -> 0.0;
    private LongSupplier totalTrades = () -> 0L;

    public ExchangeControlService(MatchingEngineService matching, MarketDataService marketData,
                                  CancelReporter cancelReporter, LongSupplier clock) {
        this.matching = matching;
        this.marketData = marketData;
        this.cancelReporter = cancelReporter;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /**
     * Attach the live-feed metrics shown in the status pane. Wired separately because the feed
     * service is optional and is built after this service.
     */
    public void setFeedMetrics(IntSupplier wsClients, DoubleSupplier tradesPerSec, LongSupplier totalTrades) {
        this.wsClients = wsClients;
        this.tradesPerSec = tradesPerSec;
        this.totalTrades = totalTrades;
    }

    private TradingSession session() {
        return matching.session();
    }

    // --- status ---

    public ExchangeStatus status() {
        List<SymbolStatus> symbols = new ArrayList<>();
        List<Instrument> listed = new ArrayList<>(matching.engine().instruments());
        listed.sort(Comparator.comparing(Instrument::symbol));
        for (Instrument instrument : listed) {
            String symbol = instrument.symbol();
            Optional<OrderBook> book = matching.engine().book(symbol);
            symbols.add(new SymbolStatus(
                    symbol,
                    session().state(symbol),
                    book.flatMap(OrderBook::bestBid).orElse(null),
                    book.flatMap(OrderBook::bestAsk).orElse(null),
                    marketData.lastSale(symbol).map(OrderBook.Level::price).orElse(null),
                    book.map(OrderBook::restingCount).orElse(0)));
        }
        return new ExchangeStatus(session().marketState(), clock.getAsLong() - startedAt,
                wsClients.getAsInt(), tradesPerSec.getAsDouble(), totalTrades.getAsLong(), symbols);
    }

    /**
     * Aggregated depth for one symbol.
     *
     * @param depth levels per side; {@code <= 0} means the full book
     * @return empty if the symbol is not listed
     */
    public Optional<BookSnapshot> book(String symbol, int depth) {
        int levels = depth <= 0 ? Integer.MAX_VALUE : depth;
        return matching.engine().book(symbol)
                .map(b -> new BookSnapshot(symbol, b.bidLevels(levels), b.askLevels(levels)));
    }

    // --- controls ---

    /** Halt trading market-wide, or for one symbol when {@code symbol} is non-null. */
    public ControlResult halt(String symbol) {
        if (symbol == null) {
            session().halt();
        } else {
            session().halt(symbol);
        }
        return new ControlResult(session().marketState(), symbol, 0);
    }

    /** Resume trading market-wide, or for one symbol when {@code symbol} is non-null. */
    public ControlResult open(String symbol) {
        if (symbol == null) {
            session().open();
        } else {
            session().open(symbol);
        }
        return new ControlResult(session().marketState(), symbol, 0);
    }

    /**
     * Cancel every resting order for one symbol, or across all books when {@code symbol} is null,
     * and report each cancellation to its owning broker.
     */
    public ControlResult clearBook(String symbol) {
        List<Order> cancelled = matching.clearBook(symbol);
        for (Order order : cancelled) {
            cancelReporter.reportCancelled(order);
        }
        return new ControlResult(session().marketState(), symbol, cancelled.size());
    }

    /** Listed symbols, sorted — the console's symbol picker. */
    public List<String> symbols() {
        return matching.engine().instruments().stream().map(Instrument::symbol).sorted().toList();
    }
}
