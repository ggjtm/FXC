package com.fxc.exchange.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.EquityInstrument;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive tests of the price-time-priority matching core (the highest-value test target,
 * PLAN Phase 1). Covers both FX and equity instruments, since matching must be asset-class
 * agnostic.
 */
class OrderBookTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    private static final Instrument EURUSD =
            FxSpotInstrument.of(EUR, USD, new BigDecimal("0.00001"), new BigDecimal("1000"));
    private static final Instrument ACME =
            EquityInstrument.of("ACME", "Acme Corp", USD, new BigDecimal("0.01"), BigDecimal.ONE);

    private final AtomicLong seq = new AtomicLong();

    private OrderBook book(Instrument instrument) {
        return new OrderBook(instrument, seq::incrementAndGet);
    }

    private Order limit(String id, String broker, Instrument i, Side side, String price, String qty) {
        return new Order(id, broker, i.symbol(), side, OrderType.LIMIT,
                new BigDecimal(price), new BigDecimal(qty), seq.incrementAndGet());
    }

    private Order market(String id, String broker, Instrument i, Side side, String qty) {
        return new Order(id, broker, i.symbol(), side, OrderType.MARKET,
                null, new BigDecimal(qty), seq.incrementAndGet());
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void nonCrossingLimitRestsWithNoTrades() {
        OrderBook b = book(EURUSD);
        Order bid = limit("b1", "brk", EURUSD, Side.BUY, "1.08000", "1000");
        List<Trade> trades = b.submit(bid);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.NEW, bid.status());
        eq("1.08000", b.bestBid().orElseThrow());
        assertTrue(b.bestAsk().isEmpty());
    }

    @Test
    void fullFillAtRestingPrice() {
        OrderBook b = book(EURUSD);
        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "1000");
        List<Trade> trades = b.submit(buy);

        assertEquals(1, trades.size());
        Trade t = trades.get(0);
        eq("1.08420", t.price());
        eq("1000", t.quantity());
        assertEquals("buy1", t.buyOrderId());
        assertEquals("ask1", t.sellOrderId());
        assertEquals("taker", t.buyBroker());
        assertEquals("mm", t.sellBroker());
        assertEquals(Side.BUY, t.aggressorSide());
        assertEquals(OrderStatus.FILLED, buy.status());
        assertTrue(b.bestBid().isEmpty());
        assertTrue(b.bestAsk().isEmpty());
    }

    @Test
    void aggressorGetsPriceImprovement() {
        OrderBook b = book(EURUSD);
        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08400", "1000"));
        // Buyer willing to pay 1.08450 but executes at the resting 1.08400.
        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08450", "1000");
        List<Trade> trades = b.submit(buy);

        eq("1.08400", trades.get(0).price());
        assertEquals(OrderStatus.FILLED, buy.status());
    }

    @Test
    void partialFillRestsRemainder() {
        OrderBook b = book(EURUSD);
        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "3000");
        List<Trade> trades = b.submit(buy);

        assertEquals(1, trades.size());
        eq("1000", trades.get(0).quantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.status());
        eq("2000", buy.leavesQty());
        eq("1.08420", b.bestBid().orElseThrow()); // remainder now rests as the bid
        assertTrue(b.bestAsk().isEmpty());
    }

    @Test
    void restingSideFullyConsumedRemovesLevel() {
        OrderBook b = book(EURUSD);
        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "1000");
        b.submit(buy);
        assertTrue(b.bestAsk().isEmpty());
    }

    @Test
    void timePriorityFifoWithinPriceLevel() {
        OrderBook b = book(EURUSD);
        Order first = limit("a1", "mm1", EURUSD, Side.SELL, "1.08420", "1000");
        Order second = limit("a2", "mm2", EURUSD, Side.SELL, "1.08420", "1000");
        b.submit(first);
        b.submit(second);

        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "1000");
        List<Trade> trades = b.submit(buy);

        assertEquals(1, trades.size());
        assertEquals("a1", trades.get(0).sellOrderId()); // earliest at the level fills first
        assertEquals(OrderStatus.FILLED, first.status());
        assertEquals(OrderStatus.NEW, second.status());
    }

    @Test
    void bestPriceFirstAcrossLevels() {
        OrderBook b = book(EURUSD);
        b.submit(limit("a_hi", "mm", EURUSD, Side.SELL, "1.08430", "1000"));
        b.submit(limit("a_lo", "mm", EURUSD, Side.SELL, "1.08420", "1000"));

        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "1000");
        List<Trade> trades = b.submit(buy);

        assertEquals("a_lo", trades.get(0).sellOrderId()); // best (lowest) ask fills first
    }

    @Test
    void marketOrderSweepsMultipleLevelsThenCancelsRemainder() {
        OrderBook b = book(EURUSD);
        b.submit(limit("a_lo", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        b.submit(limit("a_hi", "mm", EURUSD, Side.SELL, "1.08430", "1000"));

        Order buy = market("mkt1", "taker", EURUSD, Side.BUY, "5000");
        List<Trade> trades = b.submit(buy);

        assertEquals(2, trades.size());
        eq("1.08420", trades.get(0).price());
        eq("1.08430", trades.get(1).price());
        eq("2000", buy.cumQty());
        assertEquals(OrderStatus.CANCELLED, buy.status()); // unfilled remainder cancelled (IOC)
        assertTrue(b.bestAsk().isEmpty());
    }

    @Test
    void marketOrderIntoEmptyBookIsCancelled() {
        OrderBook b = book(EURUSD);
        Order buy = market("mkt1", "taker", EURUSD, Side.BUY, "1000");
        List<Trade> trades = b.submit(buy);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.CANCELLED, buy.status());
        eq("0", buy.cumQty());
    }

    @Test
    void nonCrossingLimitDoesNotMatch() {
        OrderBook b = book(EURUSD);
        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        // Bid below the ask: no cross, rests.
        Order buy = limit("buy1", "taker", EURUSD, Side.BUY, "1.08410", "1000");
        List<Trade> trades = b.submit(buy);

        assertTrue(trades.isEmpty());
        assertEquals(OrderStatus.NEW, buy.status());
        eq("1.08410", b.bestBid().orElseThrow());
        eq("1.08420", b.bestAsk().orElseThrow());
    }

    @Test
    void cancelRestingOrderRemovesIt() {
        OrderBook b = book(EURUSD);
        Order bid = limit("b1", "brk", EURUSD, Side.BUY, "1.08000", "1000");
        b.submit(bid);
        assertTrue(b.cancel("b1").isPresent());
        assertEquals(OrderStatus.CANCELLED, bid.status());
        assertTrue(b.bestBid().isEmpty());
    }

    @Test
    void cancelUnknownOrFilledReturnsEmpty() {
        OrderBook b = book(EURUSD);
        assertTrue(b.cancel("nope").isEmpty());

        b.submit(limit("ask1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        b.submit(limit("buy1", "taker", EURUSD, Side.BUY, "1.08420", "1000"));
        assertTrue(b.cancel("ask1").isEmpty()); // fully filled, no longer resting
    }

    @Test
    void depthAggregatesQuantityPerLevelBestFirst() {
        OrderBook b = book(EURUSD);
        b.submit(limit("a1", "mm", EURUSD, Side.SELL, "1.08420", "1000"));
        b.submit(limit("a2", "mm", EURUSD, Side.SELL, "1.08420", "2000"));
        b.submit(limit("a3", "mm", EURUSD, Side.SELL, "1.08430", "1000"));

        List<OrderBook.Level> levels = b.askLevels(10);
        assertEquals(2, levels.size());
        eq("1.08420", levels.get(0).price());
        eq("3000", levels.get(0).quantity());
        eq("1.08430", levels.get(1).price());
        eq("1000", levels.get(1).quantity());
    }

    @Test
    void equityInstrumentMatchesToo() {
        OrderBook b = book(ACME);
        b.submit(limit("ask1", "mm", ACME, Side.SELL, "42.10", "100"));
        Order buy = limit("buy1", "taker", ACME, Side.BUY, "42.10", "100");
        List<Trade> trades = b.submit(buy);

        assertEquals(1, trades.size());
        assertEquals("ACME", trades.get(0).symbol());
        eq("42.10", trades.get(0).price());
        assertEquals(OrderStatus.FILLED, buy.status());
    }

    @Test
    void sellAggressorHitsBids() {
        OrderBook b = book(EURUSD);
        b.submit(limit("bid1", "mm", EURUSD, Side.BUY, "1.08420", "1000"));
        Order sell = limit("sell1", "taker", EURUSD, Side.SELL, "1.08420", "1000");
        List<Trade> trades = b.submit(sell);

        Trade t = trades.get(0);
        assertEquals(Side.SELL, t.aggressorSide());
        assertEquals("bid1", t.buyOrderId());
        assertEquals("sell1", t.sellOrderId());
        assertSame(OrderStatus.FILLED, sell.status());
    }

    // --- engine-level: validation, unknown instrument, seeding ---

    @Test
    void engineRejectsUnknownInstrument() {
        MatchingEngine engine = new MatchingEngine();
        MatchResult r = engine.submit(new NewOrder("o1", "brk", "ZZZ", Side.BUY,
                OrderType.LIMIT, new BigDecimal("1.0"), new BigDecimal("1000")));
        assertFalse(r.accepted());
        assertEquals(OrderStatus.REJECTED, r.order().status());
        assertTrue(r.rejectReason().contains("unknown instrument"));
    }

    @Test
    void engineRejectsTickAndLotViolations() {
        MatchingEngine engine = new MatchingEngine();
        engine.list(EURUSD);

        MatchResult badTick = engine.submit(new NewOrder("o1", "brk", "EUR/USD", Side.BUY,
                OrderType.LIMIT, new BigDecimal("1.084203"), new BigDecimal("1000")));
        assertFalse(badTick.accepted());
        assertTrue(badTick.rejectReason().contains("tick"));

        MatchResult badLot = engine.submit(new NewOrder("o2", "brk", "EUR/USD", Side.BUY,
                OrderType.LIMIT, new BigDecimal("1.08420"), new BigDecimal("1500")));
        assertFalse(badLot.accepted());
        assertTrue(badLot.rejectReason().contains("lot"));
    }

    @Test
    void engineCrossesTwoBrokersAndIndexesOrders() {
        MatchingEngine engine = new MatchingEngine();
        engine.list(EURUSD);

        engine.submit(new NewOrder("mm-1", "mm", "EUR/USD", Side.SELL,
                OrderType.LIMIT, new BigDecimal("1.08420"), new BigDecimal("1000")));
        MatchResult taker = engine.submit(new NewOrder("tk-1", "taker", "EUR/USD", Side.BUY,
                OrderType.LIMIT, new BigDecimal("1.08420"), new BigDecimal("1000")));

        assertTrue(taker.accepted());
        assertEquals(1, taker.trades().size());
        assertEquals(OrderStatus.FILLED, engine.order("tk-1").orElseThrow().status());
        assertEquals(OrderStatus.FILLED, engine.order("mm-1").orElseThrow().status());
    }

    @Test
    void engineCancelDelegatesToBook() {
        MatchingEngine engine = new MatchingEngine();
        engine.list(ACME);
        engine.submit(new NewOrder("o1", "brk", "ACME", Side.BUY,
                OrderType.LIMIT, new BigDecimal("42.10"), new BigDecimal("100")));

        assertTrue(engine.cancel("o1").isPresent());
        assertEquals(OrderStatus.CANCELLED, engine.order("o1").orElseThrow().status());
        assertTrue(engine.cancel("o1").isEmpty()); // already cancelled
    }
}
