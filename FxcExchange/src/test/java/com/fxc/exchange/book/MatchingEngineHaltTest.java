package com.fxc.exchange.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.EquityInstrument;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Halt enforcement and mass cancel in the matching engine — the two backend primitives the exchange
 * console's dropdown controls drive (docs/DESIGN.md §6).
 */
class MatchingEngineHaltTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private static final Instrument EURUSD =
            FxSpotInstrument.of(EUR, USD, new BigDecimal("0.00001"), new BigDecimal("1000"));
    private static final Instrument ACME =
            EquityInstrument.of("ACME", "Acme Corp", USD, new BigDecimal("0.01"), BigDecimal.ONE);

    private static MatchingEngine engine(TradingSession session) {
        MatchingEngine engine = new MatchingEngine(session);
        engine.list(EURUSD);
        engine.list(ACME);
        return engine;
    }

    private static NewOrder buy(String id, String broker, String symbol, String price, String qty) {
        return new NewOrder(id, broker, symbol, Side.BUY, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(qty));
    }

    private static NewOrder sell(String id, String broker, String symbol, String price, String qty) {
        return new NewOrder(id, broker, symbol, Side.SELL, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(qty));
    }

    // --- halt gate ---

    @Test
    void haltedMarketRejectsNewOrders() {
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);
        session.halt();

        MatchResult result = engine.submit(buy("o1", "brk", "ACME", "42.10", "100"));

        assertFalse(result.accepted());
        assertEquals(OrderStatus.REJECTED, result.order().status());
        assertTrue(result.rejectReason().contains("trading halted"),
                "reject reason should say why: " + result.rejectReason());
        assertTrue(result.trades().isEmpty());
        assertTrue(engine.book("ACME").orElseThrow().bidLevels(5).isEmpty(), "nothing may rest while halted");
    }

    @Test
    void perSymbolHaltRejectsOnlyThatSymbol() {
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);
        session.halt("ACME");

        assertFalse(engine.submit(buy("o1", "brk", "ACME", "42.10", "100")).accepted());
        assertTrue(engine.submit(buy("o2", "brk", "EUR/USD", "1.08000", "1000")).accepted());
    }

    @Test
    void resumingReAcceptsOrders() {
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);

        session.halt();
        assertFalse(engine.submit(buy("o1", "brk", "ACME", "42.10", "100")).accepted());

        session.open();
        MatchResult after = engine.submit(buy("o2", "brk", "ACME", "42.10", "100"));
        assertTrue(after.accepted());
        assertEquals(OrderStatus.NEW, after.order().status());
    }

    @Test
    void haltIsCheckedBeforeInstrumentAndValidation() {
        // A halted market must reject for the halt, not for an incidental second fault — otherwise
        // an operator watching rejects cannot tell the market is closed.
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);
        session.halt();

        MatchResult unknown = engine.submit(buy("o1", "brk", "ZZZ", "1.00", "1"));
        assertTrue(unknown.rejectReason().contains("trading halted"));

        MatchResult badTick = engine.submit(buy("o2", "brk", "EUR/USD", "1.084203", "1000"));
        assertTrue(badTick.rejectReason().contains("trading halted"));
    }

    @Test
    void rejectedHaltedOrdersAreStillIndexedForReporting() {
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);
        session.halt();
        engine.submit(buy("o1", "brk", "ACME", "42.10", "100"));

        assertEquals(OrderStatus.REJECTED, engine.order("o1").orElseThrow().status());
    }

    @Test
    void cancelStillWorksWhileHalted() {
        // Standard market behaviour: a halt stops new orders but must not trap resting ones.
        TradingSession session = new TradingSession();
        MatchingEngine engine = engine(session);
        engine.submit(buy("o1", "brk", "ACME", "42.10", "100"));

        session.halt();

        assertTrue(engine.cancel("o1").isPresent(), "brokers must be able to withdraw orders during a halt");
        assertEquals(OrderStatus.CANCELLED, engine.order("o1").orElseThrow().status());
    }

    // --- clear book ---

    @Test
    void clearBookCancelsEveryRestingOrderOnBothSides() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brkA", "ACME", "42.00", "100"));
        engine.submit(buy("b2", "brkB", "ACME", "41.90", "200"));
        engine.submit(sell("s1", "brkA", "ACME", "42.50", "150"));

        List<Order> cancelled = engine.clearBook("ACME");

        assertEquals(3, cancelled.size());
        assertTrue(cancelled.stream().allMatch(o -> o.status() == OrderStatus.CANCELLED));
        OrderBook book = engine.book("ACME").orElseThrow();
        assertTrue(book.bidLevels(10).isEmpty());
        assertTrue(book.askLevels(10).isEmpty());
        assertTrue(book.bestBid().isEmpty());
        assertTrue(book.bestAsk().isEmpty());
    }

    @Test
    void clearBookReportsOwningBrokersSoOmsStateCanBeReconciled() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brkA", "ACME", "42.00", "100"));
        engine.submit(sell("s1", "brkB", "ACME", "42.50", "150"));

        List<Order> cancelled = engine.clearBook("ACME");

        assertEquals(java.util.Set.of("brkA", "brkB"),
                cancelled.stream().map(Order::broker).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void clearBookLeavesOtherSymbolsAlone() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brk", "ACME", "42.00", "100"));
        engine.submit(buy("b2", "brk", "EUR/USD", "1.08000", "1000"));

        engine.clearBook("ACME");

        assertTrue(engine.book("ACME").orElseThrow().bestBid().isEmpty());
        assertTrue(engine.book("EUR/USD").orElseThrow().bestBid().isPresent());
    }

    @Test
    void clearBookPreservesPartialFillsWhenCancellingTheRemainder() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(sell("s1", "brkA", "ACME", "42.00", "100"));
        engine.submit(buy("b1", "brkB", "ACME", "42.00", "40")); // partially fills s1

        List<Order> cancelled = engine.clearBook("ACME");

        assertEquals(1, cancelled.size());
        Order remainder = cancelled.get(0);
        assertEquals("s1", remainder.orderId());
        assertEquals(OrderStatus.CANCELLED, remainder.status());
        assertEquals(0, new BigDecimal("40").compareTo(remainder.cumQty()), "the fill must survive the cancel");
        assertEquals(0, new BigDecimal("60").compareTo(remainder.leavesQty()));
    }

    @Test
    void clearBookOnEmptyOrUnknownSymbolIsANoOp() {
        MatchingEngine engine = engine(new TradingSession());
        assertTrue(engine.clearBook("ACME").isEmpty());
        assertTrue(engine.clearBook("NOSUCH").isEmpty());
    }

    @Test
    void clearAllEmptiesEveryBook() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brk", "ACME", "42.00", "100"));
        engine.submit(sell("s1", "brk", "ACME", "42.50", "100"));
        engine.submit(buy("b2", "brk", "EUR/USD", "1.08000", "1000"));

        List<Order> cancelled = engine.clearAll();

        assertEquals(3, cancelled.size());
        assertTrue(engine.book("ACME").orElseThrow().bestBid().isEmpty());
        assertTrue(engine.book("ACME").orElseThrow().bestAsk().isEmpty());
        assertTrue(engine.book("EUR/USD").orElseThrow().bestBid().isEmpty());
    }

    @Test
    void clearedBookAcceptsNewOrdersAgain() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brk", "ACME", "42.00", "100"));
        engine.clearBook("ACME");

        assertTrue(engine.submit(buy("b2", "brk", "ACME", "42.00", "100")).accepted());
        assertEquals(0, new BigDecimal("42.00").compareTo(
                engine.book("ACME").orElseThrow().bestBid().orElseThrow()));
    }

    @Test
    void cancelAfterClearBookIsANoOpRatherThanADoubleCancel() {
        MatchingEngine engine = engine(new TradingSession());
        engine.submit(buy("b1", "brk", "ACME", "42.00", "100"));
        engine.clearBook("ACME");

        assertTrue(engine.cancel("b1").isEmpty(), "the order is no longer resting");
    }
}
