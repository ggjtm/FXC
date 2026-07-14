package com.fxc.exchange.feed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The exchange's REST + web-UI transport (FxcExchange/docs/stories/001). Uses the JDK's built-in
 * {@link HttpServer} — no web framework, matching {@code FxcBroker}'s OFX server. Serves:
 *
 * <ul>
 *   <li>{@code GET /api/symbols} — traded symbols;</li>
 *   <li>{@code GET /api/candles?symbol&start&end&granularity} — OHLCV candles + volume-by-price for
 *       the window, with the granularity actually applied (age-based floors);</li>
 *   <li>{@code GET /api/config} — the live-feed WebSocket port;</li>
 *   <li>{@code GET /} — the self-contained charting UI (classpath {@code web/index.html}).</li>
 * </ul>
 */
public final class FeedHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final CandleService candles;
    private final int wsPort;

    public FeedHttpServer(String host, int port, CandleService candles, int wsPort) throws IOException {
        this.candles = candles;
        this.wsPort = wsPort;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/symbols", this::handleSymbols);
        server.createContext("/api/candles", this::handleCandles);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/", this::handleUi);
    }

    public void start() {
        server.start();
    }

    public int boundPort() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- handlers ---

    private void handleSymbols(HttpExchange ex) throws IOException {
        if (notGet(ex)) {
            return;
        }
        String json = Json.array(candles.symbols(), Json::str);
        sendJson(ex, 200, json);
    }

    private void handleConfig(HttpExchange ex) throws IOException {
        if (notGet(ex)) {
            return;
        }
        sendJson(ex, 200, "{\"wsPort\":" + wsPort + "}");
    }

    private void handleCandles(HttpExchange ex) throws IOException {
        if (notGet(ex)) {
            return;
        }
        Map<String, String> q = query(ex.getRequestURI());
        String symbol = q.get("symbol");
        if (symbol == null || symbol.isBlank()) {
            sendJson(ex, 400, "{\"error\":\"symbol required\"}");
            return;
        }
        long now = System.currentTimeMillis();
        long end = parseLong(q.get("end"), now);
        long start = parseLong(q.get("start"), end - Granularities.DAY_MS);
        long gran = q.containsKey("granularity")
                ? Granularities.parse(q.get("granularity")) : Granularities.MINUTE_MS;

        CandleResponse resp = candles.candles(symbol, start, end, gran);
        sendJson(ex, 200, candleJson(resp));
    }

    private void handleUi(HttpExchange ex) throws IOException {
        if (notGet(ex)) {
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] page = readResource("web/index.html");
        if (page == null) {
            send(ex, 500, "text/plain", "UI resource missing".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(ex, 200, "text/html; charset=utf-8", page);
    }

    // --- JSON rendering ---

    static String candleJson(CandleResponse r) {
        String candlesArr = Json.array(r.candles(), c ->
                "{\"t\":" + c.startMs()
                        + ",\"o\":" + Json.num(c.open())
                        + ",\"h\":" + Json.num(c.high())
                        + ",\"l\":" + Json.num(c.low())
                        + ",\"c\":" + Json.num(c.close())
                        + ",\"v\":" + Json.num(c.volume()) + "}");
        String byPriceArr = Json.array(r.volumeByPrice(), pv ->
                "{\"price\":" + Json.num(pv.price()) + ",\"volume\":" + Json.num(pv.volume()) + "}");
        return "{\"symbol\":" + Json.str(r.symbol())
                + ",\"start\":" + r.start()
                + ",\"end\":" + r.end()
                + ",\"granularityMs\":" + r.granularityMs()
                + ",\"candles\":" + candlesArr
                + ",\"volumeByPrice\":" + byPriceArr + "}";
    }

    // --- helpers ---

    private static boolean notGet(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return true;
        }
        return false;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                out.put(kv.substring(0, eq),
                        java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isBlank()) {
            return dflt;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] readResource(String name) {
        try (InputStream in = FeedHttpServer.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
