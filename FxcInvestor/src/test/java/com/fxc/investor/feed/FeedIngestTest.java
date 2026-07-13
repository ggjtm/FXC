package com.fxc.investor.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.investor.strategy.MarketView;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for parsing FxcPub fill statuses into the MarketView (no XMPP needed). */
class FeedIngestTest {

    @Test
    void parsesFillStatusIntoLastSaleAndTradedVolume() {
        MarketView market = new MarketView();
        FeedClient.ingest("<status xmlns='fxc:status'>FILLED: BUY 100 ACME @ 42.1</status>", market);

        assertEquals(0, market.lastSale("ACME").orElseThrow().compareTo(new BigDecimal("42.1")));
        assertEquals(0, market.tradedVolume("ACME").get(new BigDecimal("42.1")).compareTo(new BigDecimal("100")));
    }

    @Test
    void accumulatesTradedVolumeAcrossFills() {
        MarketView market = new MarketView();
        FeedClient.ingest("FILLED: BUY 100 ACME @ 42.1", market);
        FeedClient.ingest("FILLED: SELL 50 ACME @ 42.1", market);
        FeedClient.ingest("FILLED: BUY 30 ACME @ 42.2", market);

        assertEquals(0, market.tradedVolume("ACME").get(new BigDecimal("42.1")).compareTo(new BigDecimal("150")));
        assertEquals(0, market.tradedVolume("ACME").get(new BigDecimal("42.2")).compareTo(new BigDecimal("30")));
        assertEquals(0, market.lastSale("ACME").orElseThrow().compareTo(new BigDecimal("42.2")));
    }

    @Test
    void ignoresNonFillItems() {
        MarketView market = new MarketView();
        FeedClient.ingest("<status xmlns='fxc:status'>hello world</status>", market);
        assertTrue(market.lastSale("ACME").isEmpty());
    }
}
