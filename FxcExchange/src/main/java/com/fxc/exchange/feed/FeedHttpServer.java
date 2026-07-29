package com.fxc.exchange.feed;

import com.fxc.common.web.HttpJson;
import com.fxc.common.web.Json;
import com.fxc.common.web.StaticAssets;
import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.control.ExchangeControlService;
import com.fxc.exchange.control.ExchangeControlService.BookSnapshot;
import com.fxc.exchange.control.ExchangeControlService.ControlResult;
import com.fxc.exchange.control.ExchangeControlService.ExchangeStatus;
import com.fxc.exchange.control.ExchangeControlService.SymbolStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The exchange's REST + web-console transport (FxcExchange/docs/stories/001 and 002). Uses the JDK's
 * built-in {@link HttpServer} — no web framework, matching {@code FxcBroker}'s OFX server. Serves:
 *
 * <ul>
 *   <li>{@code GET /api/symbols} — listed symbols;</li>
 *   <li>{@code GET /api/candles?symbol&start&end&granularity} — OHLCV candles + volume-by-price for
 *       the window, with the granularity actually applied (age-based floors);</li>
 *   <li>{@code GET /api/config} — the live-feed WebSocket port and whether controls are enabled;</li>
 *   <li>{@code GET /api/status} — market state, per-symbol quotes/depth, feed throughput;</li>
 *   <li>{@code GET /api/book?symbol&depth} — aggregated book depth;</li>
 *   <li>{@code POST /api/session/halt|open?symbol} — halt/resume trading (market-wide, or one symbol);</li>
 *   <li>{@code POST /api/book/clear?symbol} — mass-cancel resting orders;</li>
 *   <li>{@code GET /}, {@code /assets/*}, {@code /common/*} — the console and its assets.</li>
 * </ul>
 *
 * <p>Control endpoints are {@code POST} with query parameters and an empty body, deliberately: FXC
 * has no JSON parser and does not need one for this surface (see {@link Json}). They are
 * unauthenticated, which is why they sit behind a config switch — see docs/DESIGN.md §7.
 */
public final class FeedHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final CandleService candles;
    private final int wsPort;
    private final ExchangeControlService control; // nullable
    private final boolean controlsEnabled;

    public FeedHttpServer(String host, int port, CandleService candles, int wsPort) throws IOException {
        this(host, port, candles, wsPort, null, false);
    }

    /**
     * @param control         the control/status service, or {@code null} to serve history only
     * @param controlsEnabled when false, the mutating {@code POST} endpoints are not registered
     */
    public FeedHttpServer(String host, int port, CandleService candles, int wsPort,
                          ExchangeControlService control, boolean controlsEnabled) throws IOException {
        this.candles = candles;
        this.wsPort = wsPort;
        this.control = control;
        this.controlsEnabled = control != null && controlsEnabled;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/symbols", this::handleSymbols);
        server.createContext("/api/candles", this::handleCandles);
        server.createContext("/api/config", this::handleConfig);
        if (control != null) {
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/book", this::handleBook);
        }
        if (this.controlsEnabled) {
            server.createContext("/api/session/halt", ex -> handleSession(ex, true));
            server.createContext("/api/session/open", ex -> handleSession(ex, false));
            server.createContext("/api/book/clear", this::handleClearBook);
        }
        // Catch-all: the console page plus its own and the shared fxc-common assets.
        server.createContext("/", new StaticAssets("web/index.html")
                .mount("/assets/", "web/assets/")
                .mount("/common/", "web/common/"));
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

    // --- history handlers ---

    private void handleSymbols(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/symbols") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, Json.array(candles.symbols(), Json::str));
    }

    private void handleConfig(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/config") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, "{\"wsPort\":" + wsPort
                + ",\"controlsEnabled\":" + controlsEnabled + "}");
    }

    private void handleCandles(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/candles") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        Map<String, String> q = HttpJson.query(ex.getRequestURI());
        String symbol = HttpJson.optional(q, "symbol");
        if (symbol == null) {
            HttpJson.sendError(ex, 400, "symbol required");
            return;
        }
        long now = System.currentTimeMillis();
        long end = HttpJson.parseLong(q.get("end"), now);
        long start = HttpJson.parseLong(q.get("start"), end - Granularities.DAY_MS);
        long gran;
        try {
            gran = q.containsKey("granularity")
                    ? Granularities.parse(q.get("granularity")) : Granularities.MINUTE_MS;
        } catch (IllegalArgumentException e) {
            // An unusable granularity token is a bad request, not a server fault.
            HttpJson.sendError(ex, 400, "invalid granularity: " + q.get("granularity"));
            return;
        }

        HttpJson.sendJson(ex, 200, candleJson(candles.candles(symbol, start, end, gran)));
    }

    // --- status / control handlers ---

    private void handleStatus(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/status") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, statusJson(control.status()));
    }

    private void handleBook(HttpExchange ex) throws IOException {
        // Exact-path first: /api/book/clear must not be absorbed by this GET-only prefix.
        if (HttpJson.requireExactPath(ex, "/api/book") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        Map<String, String> q = HttpJson.query(ex.getRequestURI());
        String symbol = HttpJson.optional(q, "symbol");
        if (symbol == null) {
            HttpJson.sendError(ex, 400, "symbol required");
            return;
        }
        Optional<BookSnapshot> book = control.book(symbol, HttpJson.parseInt(q.get("depth"), 10));
        if (book.isEmpty()) {
            HttpJson.sendError(ex, 404, "unknown symbol: " + symbol);
            return;
        }
        HttpJson.sendJson(ex, 200, bookJson(book.get()));
    }

    private void handleSession(HttpExchange ex, boolean halt) throws IOException {
        String path = "/api/session/" + (halt ? "halt" : "open");
        if (HttpJson.requireExactPath(ex, path) || HttpJson.requireMethod(ex, "POST")) {
            return;
        }
        String symbol = HttpJson.optional(HttpJson.query(ex.getRequestURI()), "symbol");
        ControlResult result = halt ? control.halt(symbol) : control.open(symbol);
        HttpJson.sendJson(ex, 200, controlJson(result));
    }

    private void handleClearBook(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/book/clear") || HttpJson.requireMethod(ex, "POST")) {
            return;
        }
        String symbol = HttpJson.optional(HttpJson.query(ex.getRequestURI()), "symbol");
        HttpJson.sendJson(ex, 200, controlJson(control.clearBook(symbol)));
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

    static String statusJson(ExchangeStatus s) {
        return "{\"marketState\":" + Json.str(s.marketState().name())
                + ",\"uptimeMs\":" + s.uptimeMs()
                + ",\"wsClients\":" + s.wsClients()
                + ",\"tradesPerSec\":" + round2(s.tradesPerSec())
                + ",\"totalTrades\":" + s.totalTrades()
                + ",\"symbols\":" + Json.array(s.symbols(), FeedHttpServer::symbolStatusJson) + "}";
    }

    private static String symbolStatusJson(SymbolStatus s) {
        return "{\"symbol\":" + Json.str(s.symbol())
                + ",\"state\":" + Json.str(s.state().name())
                + ",\"bestBid\":" + Json.num(s.bestBid())
                + ",\"bestAsk\":" + Json.num(s.bestAsk())
                + ",\"lastPrice\":" + Json.num(s.lastPrice())
                + ",\"restingOrders\":" + s.restingOrders() + "}";
    }

    static String bookJson(BookSnapshot b) {
        return "{\"symbol\":" + Json.str(b.symbol())
                + ",\"bids\":" + Json.array(b.bids(), FeedHttpServer::levelJson)
                + ",\"asks\":" + Json.array(b.asks(), FeedHttpServer::levelJson) + "}";
    }

    private static String levelJson(OrderBook.Level level) {
        return "{\"price\":" + Json.num(level.price()) + ",\"size\":" + Json.num(level.quantity()) + "}";
    }

    static String controlJson(ControlResult r) {
        return "{\"marketState\":" + Json.str(r.marketState().name())
                + ",\"symbol\":" + (r.symbol() == null ? "null" : Json.str(r.symbol()))
                + ",\"cancelled\":" + r.cancelled() + "}";
    }

    /** Two decimals, without exponent form — the rate is display-only. */
    private static String round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
