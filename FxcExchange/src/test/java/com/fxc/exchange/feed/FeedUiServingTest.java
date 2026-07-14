package com.fxc.exchange.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionSettings;

/** The feed HTTP server serves the self-contained charting UI at {@code /} as HTML. */
class FeedUiServingTest {

    @Test
    void servesChartingUiHtml(@TempDir java.nio.file.Path workDir) throws Exception {
        try (ExchangeServer exchange = ExchangeServer.start(
                acceptorSettings(freePort()), "fxc-exchange-ui", 47563,
                workDir.toString(), InstrumentCatalog.defaults(), null, 0, 0, 0)) {

            int port = exchange.feedService().httpPort();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                    "should be served as HTML");
            String body = resp.body();
            assertTrue(body.contains("<canvas"), "UI should have a chart canvas");
            assertTrue(body.contains("/api/candles"), "UI should call the candles endpoint");
            assertTrue(body.contains("/ws?symbol="), "UI should open the live-feed websocket");
        }
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
