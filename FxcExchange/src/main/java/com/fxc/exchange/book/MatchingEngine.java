package com.fxc.exchange.book;

import com.fxc.common.instrument.Instrument;
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

    /** List an instrument, creating its (empty) book. Idempotent per symbol. */
    public void list(Instrument instrument) {
        String symbol = instrument.symbol();
        instruments.put(symbol, instrument);
        books.computeIfAbsent(symbol, s -> new OrderBook(instrument, sequence::incrementAndGet));
    }

    public synchronized MatchResult submit(NewOrder req) {
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

    public synchronized Optional<Order> cancel(String orderId) {
        OrderBook book = bookForOrder(orderId);
        if (book == null) {
            return Optional.empty();
        }
        return book.cancel(orderId);
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
