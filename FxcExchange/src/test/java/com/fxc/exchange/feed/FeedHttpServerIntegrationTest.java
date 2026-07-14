package com.fxc.exchange.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.book.NewOrder;
import com.fxc.exchange.book.OrderType;
import com.fxc.exchange.book.Side;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.exchange.service.MatchingEngineService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionSettings;

/**
 * The exchange feed REST endpoints serve traded symbols and aggregated OHLCV candles over HTTP,
 * driven by real trades through a live {@link ExchangeServer} with the feed enabled. No external
 * infra (in-memory GridGain; ephemeral HTTP/WS ports).
 */
class FeedHttpServerIntegrationTest {

    @Test
    void servesSymbolsAndCandlesOverRest(@TempDir java.nio.file.Path workDir) throws Exception {
        try (ExchangeServer exchange = ExchangeServer.start(
                acceptorSettings(freePort()), "fxc-exchange-feed", 47562,
                workDir.toString(), InstrumentCatalog.defaults(),
                null, 0, /* feed http */ 0, /* feed ws */ 0)) {

            MatchingEngineService mes = exchange.matchingService();
            // Two crossing pairs at different prices → two trades on ACME. Each resting sell is fully
            // consumed so the next buy crosses the next price level (fills execute at the resting price).
            mes.submit(order("S1", "mm", Side.SELL, "42.00", 100));
            mes.submit(order("B1", "tk", Side.BUY, "42.00", 100));  // trade @42.00 x100, book empty
            mes.submit(order("S2", "mm", Side.SELL, "42.20", 100));
            mes.submit(order("B2", "tk", Side.BUY, "42.20", 5));    // trade @42.20 x5

            int port = exchange.feedService().httpPort();
            HttpClient http = HttpClient.newHttpClient();

            // /api/symbols includes the seeded instruments.
            String symbols = get(http, port, "/api/symbols");
            assertTrue(symbols.contains("\"ACME\""), "symbols should list ACME, got: " + symbols);

            // /api/candles over a wide window returns a 1-minute candle with the two trades folded in.
            long now = System.currentTimeMillis();
            String candles = get(http, port,
                    "/api/candles?symbol=ACME&start=" + (now - Granularities.HOUR_MS)
                            + "&end=" + (now + Granularities.MINUTE_MS) + "&granularity=1m");

            assertTrue(candles.contains("\"symbol\":\"ACME\""), candles);
            assertTrue(candles.contains("\"granularityMs\":60000"), "1m granularity applied: " + candles);
            assertTrue(candles.contains("\"o\":42.00"), "open should be the first trade price: " + candles);
            assertTrue(candles.contains("\"h\":42.20"), "high across the window: " + candles);
            assertTrue(candles.contains("\"c\":42.20"), "close should be the last trade price: " + candles);
            assertTrue(candles.contains("\"v\":105"), "summed candle volume: " + candles);
            // Volume-by-price histogram carries both price points.
            assertTrue(candles.contains("\"price\":42.00,\"volume\":100")
                    && candles.contains("\"price\":42.20,\"volume\":5"), "volume-by-price: " + candles);

            // /api/config advertises the WebSocket port.
            String config = get(http, port, "/api/config");
            assertTrue(config.contains("\"wsPort\":" + exchange.feedService().wsPort()), config);
        }
    }

    private static NewOrder order(String id, String broker, Side side, String price, int qty) {
        return new NewOrder(id, broker, "ACME", side, OrderType.LIMIT, new BigDecimal(price), new BigDecimal(qty));
    }

    private static String get(HttpClient http, int port, String path) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode(), "GET " + path + " -> " + resp.statusCode() + ": " + resp.body());
        return resp.body();
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static SessionSettings acceptorSettings(int port) throws Exception {
        String cfg = """
                [DEFAULT]
                ConnectionType=acceptor
                BeginString=FIX.4.4
                SenderCompID=EXCHANGE
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                SocketAcceptPort=%d

                [SESSION]
                TargetCompID=BROKER1
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
