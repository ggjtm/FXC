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

/**
 * The feed HTTP server serves the exchange console at {@code /} plus every asset it references
 * (FxcExchange/docs/stories/002). The console is no longer one self-contained page: it pulls the
 * shared theme, the dropdown, the status indicator and the vendored D3 bundle out of the
 * {@code fxc-common} jar, so a broken mount or a missing vendored file would leave a blank page that
 * only a request for each asset can catch.
 */
class FeedUiServingTest {

    @Test
    void servesConsoleHtmlAndEveryAssetItReferences(@TempDir java.nio.file.Path workDir) throws Exception {
        try (ExchangeServer exchange = ExchangeServer.start(
                acceptorSettings(freePort()), "fxc-exchange-ui", 47563,
                workDir.toString(), InstrumentCatalog.defaults(), null, 0, 0, 0)) {

            int port = exchange.feedService().httpPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> page = get(client, port, "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").contains("text/html"),
                    "should be served as HTML");
            String body = page.body();
            assertTrue(body.contains("<svg"), "the chart is D3-rendered SVG");
            assertTrue(body.contains("/common/vendor/d3.v7.min.js"), "console should load D3");
            assertTrue(body.contains("/common/fxc.css"), "console should use the shared theme");
            assertTrue(body.contains("/assets/exchange.js"), "console should load its own script");
            assertTrue(body.contains("fxc-menu"), "console should have the hover control menu");

            // Every referenced asset must actually resolve, with a usable content type.
            HttpResponse<String> script = get(client, port, "/assets/exchange.js");
            assertEquals(200, script.statusCode());
            assertTrue(script.headers().firstValue("Content-Type").orElse("").contains("application/javascript"));
            assertTrue(script.body().contains("/api/candles"), "console should call the candles endpoint");
            assertTrue(script.body().contains("/ws?symbol="), "console should open the live-feed websocket");
            assertTrue(script.body().contains("/api/status"), "console should poll the status endpoint");

            assertEquals(200, get(client, port, "/common/fxc.css").statusCode());
            assertEquals(200, get(client, port, "/common/fxc-api.js").statusCode());
            assertEquals(200, get(client, port, "/common/fxc-menu.js").statusCode());
            assertEquals(200, get(client, port, "/common/fxc-status.js").statusCode());

            HttpResponse<String> d3 = get(client, port, "/common/vendor/d3.v7.min.js");
            assertEquals(200, d3.statusCode());
            assertTrue(d3.headers().firstValue("Content-Type").orElse("").contains("application/javascript"));
            assertTrue(d3.body().contains("d3js.org"), "expected the vendored d3 bundle");

            // Nothing else is reachable under the asset mounts.
            assertEquals(404, get(client, port, "/assets/../../etc/passwd").statusCode());
            assertEquals(404, get(client, port, "/common/nope.js").statusCode());
        }
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
