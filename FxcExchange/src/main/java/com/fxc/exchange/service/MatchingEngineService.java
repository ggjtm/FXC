package com.fxc.exchange.service;

import com.fxc.common.instrument.Instrument;
import com.fxc.exchange.book.MatchResult;
import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.book.NewOrder;
import com.fxc.exchange.book.Order;
import com.fxc.exchange.book.Side;
import com.fxc.exchange.book.Trade;
import com.fxc.exchange.book.TradingSession;
import com.fxc.exchange.grid.ExchangeRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * The exchange's order-handling service: wraps the pure {@link MatchingEngine}, persists resulting
 * state to the GridGain SQL tables via {@link ExchangeRepository}, and fans {@link ExchangeEvent}s
 * out to downstream services (market data, clearing).
 *
 * <p><b>Deployment note (docs/DESIGN.md §3.1):</b> for the single-node embedded topology, the
 * FXC services are node-hosted POJOs that use the GridGain data grid for all state, rather than
 * formal {@code org.apache.ignite.services.Service} deployments. This keeps live wiring (FIX
 * sessions, listeners) simple and avoids service-serialization subtleties; formal Service Grid
 * deployment is a mechanical wrapping deferred until multi-node scale-out is actually needed.
 */
public final class MatchingEngineService {

    private final MatchingEngine engine;
    private final ExchangeRepository repository;
    private final LongSupplier clock;
    private final List<ExchangeListener> listeners = new CopyOnWriteArrayList<>();

    public MatchingEngineService(MatchingEngine engine, ExchangeRepository repository) {
        this(engine, repository, System::currentTimeMillis);
    }

    /** Test/DI constructor with an injectable trade-timestamp clock (epoch millis). */
    public MatchingEngineService(MatchingEngine engine, ExchangeRepository repository, LongSupplier clock) {
        this.engine = engine;
        this.repository = repository;
        this.clock = clock;
    }

    /** List instruments in both the engine and the INSTRUMENT table. */
    public void seed(List<Instrument> instruments) {
        for (Instrument instrument : instruments) {
            engine.list(instrument);
            repository.upsertInstrument(instrument);
        }
    }

    public void addListener(ExchangeListener listener) {
        listeners.add(listener);
    }

    /** Submit an order: match, persist, notify. */
    public MatchResult submit(NewOrder request) {
        MatchResult result = engine.submit(request);

        repository.upsertOrder(result.order());
        if (result.accepted()) {
            long ts = clock.getAsLong();
            for (Trade trade : result.trades()) {
                repository.insertTrade(trade, ts);
                // The resting counterparty's status/cumQty changed too — persist it.
                Side restingSide = result.order().side().opposite();
                engine.order(trade.orderIdFor(restingSide)).ifPresent(repository::upsertOrder);
            }
            notifyListeners(new ExchangeEvent(request.symbol(), result.trades(), ts));
        }
        return result;
    }

    /** Cancel a resting order. */
    public Optional<Order> cancel(String orderId) {
        Optional<Order> cancelled = engine.cancel(orderId);
        cancelled.ifPresent(order -> {
            repository.upsertOrder(order);
            notifyListeners(new ExchangeEvent(order.symbol(), List.of(), clock.getAsLong()));
        });
        return cancelled;
    }

    /**
     * Clear one symbol's book, or every book when {@code symbol} is null ("clear the order book",
     * docs/DESIGN.md §6). Persists each cancelled order and fires one market-data event per affected
     * symbol so subscribers see the emptied book.
     *
     * @return the cancelled orders — the caller must report each to its owner via a
     *         {@link com.fxc.exchange.control.CancelReporter}, or brokers' OMS state will drift
     */
    public List<Order> clearBook(String symbol) {
        List<Order> cancelled = symbol == null ? engine.clearAll() : engine.clearBook(symbol);
        if (cancelled.isEmpty()) {
            return cancelled;
        }
        long ts = clock.getAsLong();
        Set<String> touched = new LinkedHashSet<>();
        for (Order order : cancelled) {
            repository.upsertOrder(order);
            touched.add(order.symbol());
        }
        for (String touchedSymbol : touched) {
            notifyListeners(new ExchangeEvent(touchedSymbol, List.of(), ts));
        }
        return cancelled;
    }

    /** The market-state switch backing the console's halt/resume controls. */
    public TradingSession session() {
        return engine.session();
    }

    public MatchingEngine engine() {
        return engine;
    }

    private void notifyListeners(ExchangeEvent event) {
        for (ExchangeListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}
