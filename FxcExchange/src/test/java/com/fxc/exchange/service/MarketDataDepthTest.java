package com.fxc.exchange.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.book.NewOrder;
import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.book.OrderType;
import com.fxc.exchange.book.Side;
import com.fxc.exchange.book.Trade;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three FIX depth tiers (FxcExchange/docs/stories/001): a subscription's requested
 * {@code MarketDepth} bounds the levels per side in the snapshot, and the last sale is echoed as a
 * TRADE entry. Drives {@link MarketDataService} against a real {@link MatchingEngine} with a capturing
 * publisher — no FIX transport needed.
 */
class MarketDataDepthTest {

    /** Captures the most recent snapshot per (target) so the test can assert on it. */
    private static final class CapturingPublisher implements MarketDataPublisher {
        List<OrderBook.Level> bids;
        List<OrderBook.Level> asks;
        OrderBook.Level lastSale;
        int snapshots;

        @Override
        public void publishSnapshot(Object target, String mdReqId, String symbol,
                                    List<OrderBook.Level> bids, List<OrderBook.Level> asks,
                                    OrderBook.Level lastSale) {
            this.bids = new ArrayList<>(bids);
            this.asks = new ArrayList<>(asks);
            this.lastSale = lastSale;
            this.snapshots++;
        }

        @Override
        public void publishIncremental(Object target, String mdReqId, String symbol,
                                       OrderBook.Level bid, OrderBook.Level ask, List<Trade> trades) {
        }
    }

    private MatchingEngine engineWithBook() {
        MatchingEngine engine = new MatchingEngine();
        InstrumentCatalog.defaults().forEach(engine::list);
        // Six resting bids and six asks at distinct prices so depth tiers differ.
        for (int i = 0; i < 6; i++) {
            engine.submit(new NewOrder("B" + i, "mm", "ACME", Side.BUY, OrderType.LIMIT,
                    new BigDecimal("41." + (90 - i)), new BigDecimal("10")));
            engine.submit(new NewOrder("A" + i, "mm", "ACME", Side.SELL, OrderType.LIMIT,
                    new BigDecimal("42." + (10 + i)), new BigDecimal("10")));
        }
        return engine;
    }

    @Test
    void topOfBookReturnsOneLevelPerSide() {
        CapturingPublisher pub = new CapturingPublisher();
        MarketDataService md = new MarketDataService(engineWithBook(), pub);
        md.subscribe("t", "req", List.of("ACME"), MarketDataService.TOP_OF_BOOK);
        assertEquals(1, pub.bids.size(), "top-of-book: one bid");
        assertEquals(1, pub.asks.size(), "top-of-book: one ask");
    }

    @Test
    void marketDepthReturnsFiveLevelsPerSide() {
        CapturingPublisher pub = new CapturingPublisher();
        MarketDataService md = new MarketDataService(engineWithBook(), pub);
        md.subscribe("t", "req", List.of("ACME"), MarketDataService.MARKET_DEPTH);
        assertEquals(5, pub.bids.size(), "market-depth: five bids");
        assertEquals(5, pub.asks.size(), "market-depth: five asks");
    }

    @Test
    void fullDepthReturnsAllLevels() {
        CapturingPublisher pub = new CapturingPublisher();
        MarketDataService md = new MarketDataService(engineWithBook(), pub);
        md.subscribe("t", "req", List.of("ACME"), MarketDataService.FULL_DEPTH);
        assertEquals(6, pub.bids.size(), "full-depth: all six bids");
        assertEquals(6, pub.asks.size(), "full-depth: all six asks");
    }

    @Test
    void lastSaleIsEchoedInSnapshotAfterATrade() {
        MatchingEngine engine = engineWithBook();
        CapturingPublisher pub = new CapturingPublisher();
        MarketDataService md = new MarketDataService(engine, pub);
        md.subscribe("t", "req", List.of("ACME"), MarketDataService.TOP_OF_BOOK);
        assertNull(pub.lastSale, "no last sale before any trade");

        // A crossing buy lifts the best offer (42.10) -> a trade; feed the event to the MD service.
        var result = engine.submit(new NewOrder("X", "tk", "ACME", Side.BUY, OrderType.LIMIT,
                new BigDecimal("42.10"), new BigDecimal("4")));
        md.onEvent(new ExchangeEvent("ACME", result.trades(), 1_000L));

        assertTrue(pub.lastSale != null, "snapshot should now carry the last sale");
        assertEquals(new BigDecimal("42.10"), pub.lastSale.price(), "last sale price");
        assertEquals(new BigDecimal("4"), pub.lastSale.quantity(), "last sale size");
    }
}
