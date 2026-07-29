package com.fxc.exchange.book;

import com.fxc.common.instrument.Instrument;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The exchange's matching core: one {@link OrderBook} per listed instrument, plus validation,
 * sequencing, and an order index. Pure domain — no GridGain, no FIX. {@code MatchingEngineService}
 * wraps this to expose it as a GridGain service and persist to tables.
 *
 * <p>All mutating operations are serialized via {@code synchronized} on this instance, giving the
 * exchange a single, deterministic order of execution across instruments. Adequate for the project
 * scale; a per-instrument striped lock is the obvious later optimization.
 */
public final class MatchingEngine {

    private final Map<String, Instrument> instruments = new ConcurrentHashMap<>();
    private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final TradingSession session;

    /** An engine with its own trading session, open for business. */
    public MatchingEngine() {
        this(new TradingSession());
    }

    /** An engine sharing an externally-controlled session (the console's halt/resume switch). */
    public MatchingEngine(TradingSession session) {
        this.session = session;
    }

    public TradingSession session() {
        return session;
    }

    /** List an instrument, creating its (empty) book. Idempotent per symbol. */
    public void list(Instrument instrument) {
        String symbol = instrument.symbol();
        instruments.put(symbol, instrument);
        books.computeIfAbsent(symbol, s -> new OrderBook(instrument, sequence::incrementAndGet));
    }

    public synchronized MatchResult submit(NewOrder req) {
        // Checked before anything else: a halted market rejects every order, including ones that
        // would otherwise fail validation, so "halted" is never masked by another reason.
        if (session.isHalted(req.symbol())) {
            Order rejected = newOrder(req);
            rejected.markRejected();
            orders.put(rejected.orderId(), rejected);
            return MatchResult.rejected(rejected, "trading halted: " + req.symbol());
        }

        Instrument instrument = instruments.get(req.symbol());
        if (instrument == null) {
            Order rejected = newOrder(req);
            rejected.markRejected();
            orders.put(rejected.orderId(), rejected);
            return MatchResult.rejected(rejected, "unknown instrument: " + req.symbol());
        }

        String error = OrderValidation.validate(instrument, req.type(), req.price(), req.quantity());
        if (error != null) {
            Order rejected = newOrder(req);
            rejected.markRejected();
            orders.put(rejected.orderId(), rejected);
            return MatchResult.rejected(rejected, error);
        }

        Order order = newOrder(req);
        orders.put(order.orderId(), order);
        List<Trade> trades = books.get(req.symbol()).submit(order);
        return MatchResult.accepted(order, trades);
    }

    /**
     * Cancel a resting order. Permitted while trading is halted — an operator halt must not trap
     * brokers' orders in the book, which is standard market behaviour.
     */
    public synchronized Optional<Order> cancel(String orderId) {
        OrderBook book = bookForOrder(orderId);
        if (book == null) {
            return Optional.empty();
        }
        return book.cancel(orderId);
    }

    /**
     * Cancel every resting order for one symbol ("clear the order book", docs/DESIGN.md §6).
     *
     * @return the cancelled orders, so the caller can report each one to its owning broker
     */
    public synchronized List<Order> clearBook(String symbol) {
        OrderBook book = books.get(symbol);
        return book == null ? List.of() : book.cancelAll();
    }

    /** Cancel every resting order across all books. */
    public synchronized List<Order> clearAll() {
        List<Order> cancelled = new ArrayList<>();
        for (OrderBook book : books.values()) {
            cancelled.addAll(book.cancelAll());
        }
        return cancelled;
    }

    public Optional<Instrument> instrument(String symbol) {
        return Optional.ofNullable(instruments.get(symbol));
    }

    public Collection<Instrument> instruments() {
        return List.copyOf(instruments.values());
    }

    public Optional<OrderBook> book(String symbol) {
        return Optional.ofNullable(books.get(symbol));
    }

    public Optional<Order> order(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    private Order newOrder(NewOrder req) {
        return new Order(req.orderId(), req.broker(), req.symbol(), req.side(), req.type(),
                req.price(), req.quantity(), sequence.incrementAndGet());
    }

    private OrderBook bookForOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            return null;
        }
        return books.get(order.symbol());
    }
}
