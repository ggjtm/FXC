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
                    .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?symbol=ARVX"),
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

            // Two ARVX trades at different prices in the window, then flush.
            feed.onEvent(new ExchangeEvent("ARVX", List.of(
                    trade("42.00", "3"), trade("42.10", "2")), 1_000_000_000_500L));
            feed.flush();

            assertTrue(received.await(10, TimeUnit.SECONDS), "a tick window should arrive over the WS");
            String msg = messages.get(0);
            assertTrue(msg.contains("\"type\":\"tick\""), msg);
            assertTrue(msg.contains("\"symbol\":\"ARVX\""), msg);
            assertTrue(msg.contains("\"volume\":5"), "summed window volume, got: " + msg);
            assertTrue(msg.contains("\"last\":42.10"), "last sale of the window, got: " + msg);
            assertTrue(msg.contains("\"price\":42.00") && msg.contains("\"price\":42.10"),
                    "volume grouped by price, got: " + msg);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            feed.close();
        }
    }

    @Test
    void quietFeedSendsHeartbeatsSoClientsCanTellSilenceFromDisconnection() throws Exception {
        try (WebSocketFeedServer server = new WebSocketFeedServer("127.0.0.1", 0)) {
            server.start();

            // A movable clock: symbols with no trades publish nothing, so without a heartbeat a
            // client cannot distinguish a quiet market from a dead socket.
            long[] now = {1_000_000_000_000L};
            LiveFeed feed = new LiveFeed(server, () -> now[0]);

            CountDownLatch received = new CountDownLatch(1);
            List<String> messages = new CopyOnWriteArrayList<>();
            WebSocket ws = connect(server.boundPort(), "*", messages, received, null);
            awaitConnection(server);

            // A quiet window right away: still inside the heartbeat interval, so nothing is sent.
            feed.flush();
            assertEquals(0, messages.size(), "no heartbeat before the interval elapses");

            // Past the interval, a quiet flush emits a heartbeat.
            now[0] += 6_000L;
            feed.flush();

            assertTrue(received.await(10, TimeUnit.SECONDS), "a heartbeat should arrive");
            String msg = messages.get(0);
            assertTrue(msg.contains("\"type\":\"heartbeat\""), msg);
            assertTrue(msg.contains("\"ts\":" + now[0]), msg);

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            feed.close();
        }
    }

    @Test
    void heartbeatReachesClientsWhateverTheirSymbolFilter() throws Exception {
        try (WebSocketFeedServer server = new WebSocketFeedServer("127.0.0.1", 0)) {
            server.start();
            long[] now = {1_000_000_000_000L};
            LiveFeed feed = new LiveFeed(server, () -> now[0]);

            CountDownLatch received = new CountDownLatch(1);
            List<String> messages = new CopyOnWriteArrayList<>();
            // Filtered to one symbol — a heartbeat is feed-level, not about any instrument.
            WebSocket ws = connect(server.boundPort(), "ARVX", messages, received, null);
            awaitConnection(server);

            now[0] += 6_000L;
            feed.flush();

            assertTrue(received.await(10, TimeUnit.SECONDS), "a symbol-filtered client still needs heartbeats");
            assertTrue(messages.get(0).contains("\"type\":\"heartbeat\""), messages.get(0));

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            feed.close();
        }
    }

    @Test
    void answersClientPingWithAPongEchoingThePayload() throws Exception {
        try (WebSocketFeedServer server = new WebSocketFeedServer("127.0.0.1", 0)) {
            server.start();

            CountDownLatch ponged = new CountDownLatch(1);
            List<String> pongs = new CopyOnWriteArrayList<>();
            WebSocket ws = connect(server.boundPort(), "*", new CopyOnWriteArrayList<>(),
                    new CountDownLatch(1), (webSocket, data) -> {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        pongs.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        ponged.countDown();
                    });
            awaitConnection(server);

            ws.sendPing(java.nio.ByteBuffer.wrap("fxc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            assertTrue(ponged.await(10, TimeUnit.SECONDS), "a ping must be answered (RFC 6455)");
            assertEquals("fxc", pongs.get(0), "the pong must echo the ping's payload");

            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    // --- helpers ---

    private interface PongHandler {
        void onPong(WebSocket webSocket, java.nio.ByteBuffer data);
    }

    private static WebSocket connect(int port, String symbol, List<String> messages,
                                     CountDownLatch received, PongHandler pongHandler) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws?symbol=" + symbol),
                        new WebSocket.Listener() {
                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                messages.add(data.toString());
                                received.countDown();
                                webSocket.request(1);
                                return null;
                            }

                            @Override
                            public CompletionStage<?> onPong(WebSocket webSocket, java.nio.ByteBuffer message) {
                                if (pongHandler != null) {
                                    pongHandler.onPong(webSocket, message);
                                }
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);
    }

    private static void awaitConnection(WebSocketFeedServer server) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (server.connectionCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, server.connectionCount(), "client should be connected");
    }

    private static Trade trade(String price, String qty) {
        return new Trade("T-" + price + "-" + qty, "ARVX", new BigDecimal(price), new BigDecimal(qty),
                "b", "s", "BROKER1", "BROKER2", Side.BUY, 1);
    }
}
