package com.fxc.exchange.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.exchange.book.Side;
import com.fxc.exchange.book.Trade;
import com.fxc.exchange.service.ExchangeEvent;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The hand-rolled WebSocket server upgrades a standard client (the JDK {@link WebSocket}), and the
 * {@link LiveFeed} pushes one-second aggregated tick windows to it. Uses the real client stack — no
 * external server needed.
 */
class WebSocketFeedServerTest {

    @Test
    void liveFeedPushesAggregatedTickWindowToWebSocketClient() throws Exception {
        try (WebSocketFeedServer server = new WebSocketFeedServer("127.0.0.1", 0)) {
            server.start();
            int port = server.boundPort();

            // Flush on demand from the test (interval scheduler not started) so timing is deterministic.
            LiveFeed feed = new LiveFeed(server, () -> 1_000_000_000_000L);

            CountDownLatch received = new CountDownLatch(1);
            List<String> messages = new CopyOnWriteArrayList<>();
            WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?symbol=ACME"),
                            new WebSocket.Listener() {
                                @Override
                                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                    messages.add(data.toString());
                                    received.countDown();
                                    webSocket.request(1);
                                    return null;
                                }
                            })
                    .get(10, TimeUnit.SECONDS);

            // Wait for the server to register the connection.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (server.connectionCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, server.connectionCount(), "client should be connected");

            // Two ACME trades at different prices in the window, then flush.
            feed.onEvent(new ExchangeEvent("ACME", List.of(
                    trade("42.00", "3"), trade("42.10", "2")), 1_000_000_000_500L));
            feed.flush();

            assertTrue(received.await(10, TimeUnit.SECONDS), "a tick window should arrive over the WS");
            String msg = messages.get(0);
            assertTrue(msg.contains("\"type\":\"tick\""), msg);
            assertTrue(msg.contains("\"symbol\":\"ACME\""), msg);
            assertTrue(msg.contains("\"volume\":5"), "summed window volume, got: " + msg);
            assertTrue(msg.contains("\"last\":42.10"), "last sale of the window, got: " + msg);
            assertTrue(msg.contains("\"price\":42.00") && msg.contains("\"price\":42.10"),
                    "volume grouped by price, got: " + msg);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            feed.close();
        }
    }

    private static Trade trade(String price, String qty) {
        return new Trade("T-" + price + "-" + qty, "ACME", new BigDecimal(price), new BigDecimal(qty),
                "b", "s", "BROKER1", "BROKER2", Side.BUY, 1);
    }
}
