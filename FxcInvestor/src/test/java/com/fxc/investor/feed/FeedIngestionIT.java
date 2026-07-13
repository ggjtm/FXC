package com.fxc.investor.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.investor.strategy.MarketView;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Proves the investor ingests live market data from the FxcPub XMPP feed: a status published to a
 * broker feed on stock Tigase is received over XMPP and folded into the {@link MarketView}
 * (last-sale + traded-volume, which drives {@code bookfish}).
 *
 * <p>Requires the Tigase container; skips when 127.0.0.1:5222 is closed.
 */
class FeedIngestionIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5222;
    private static final String DOMAIN = "fxc.local";
    private static final String BROKER = "BROKER1";

    @Test
    void investorIngestsFillStatusFromTigaseFeed() throws Exception {
        assumeTrue(reachable(HOST, PORT), "Tigase not running on " + HOST + ":" + PORT + " — skipping");

        try (FeedClient publisher = new FeedClient(HOST, PORT, DOMAIN);
             FeedClient subscriber = new FeedClient(HOST, PORT, DOMAIN)) {

            // Publisher (a trusted service account) owns/creates the feed node first.
            publisher.connect("pub-service", "secret");
            publisher.ensureFeed(BROKER);

            // Investor subscribes, then the publisher posts a fill status.
            subscriber.connect("investor", "secret");
            MarketView market = new MarketView();
            subscriber.subscribeFeed(BROKER, market);

            publisher.publishStatus(BROKER, "FILLED: BUY 100 ACME @ 42.1");

            waitUntil(() -> market.lastSale("ACME").isPresent(), 10_000);

            assertTrue(market.lastSale("ACME").isPresent(), "investor should receive the fill over XMPP");
            assertEquals(0, market.lastSale("ACME").get().compareTo(new BigDecimal("42.1")));
            assertEquals(0, market.tradedVolume("ACME").get(new BigDecimal("42.1")).compareTo(new BigDecimal("100")));
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
