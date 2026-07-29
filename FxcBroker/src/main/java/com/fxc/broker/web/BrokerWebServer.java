package com.fxc.broker.web;

import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.pnl.PnlPoint;
import com.fxc.broker.pnl.PnlService;
import com.fxc.broker.web.BrokerConsoleService.BrokerStatus;
import com.fxc.broker.web.BrokerConsoleService.LastSale;
import com.fxc.common.web.HttpJson;
import com.fxc.common.web.Json;
import com.fxc.common.web.StaticAssets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The broker's monitor/controller console and its REST surface (docs/DESIGN.md §6,
 * FxcBroker/docs/stories/002). A second JDK {@link HttpServer} on its own port, leaving the
 * POST-only OFX endpoint untouched — the two have nothing in common but a transport, and the console
 * must be disableable without affecting OFX.
 *
 * <ul>
 *   <li>{@code GET /api/status} — trading switch, exchange link, counters, uptime;</li>
 *   <li>{@code GET /api/config} — whether controls are enabled;</li>
 *   <li>{@code GET /api/accounts} — the managed accounts;</li>
 *   <li>{@code GET /api/lastsale} — last-sale ticker from the broker's market-data subscription;</li>
 *   <li>{@code GET /api/pnl} — per-account P&amp;L totals and the trade-count/P&amp;L curve;</li>
 *   <li>{@code POST /api/trading/start|stop} — stop or resume accepting client orders;</li>
 *   <li>{@code GET /}, {@code /assets/*}, {@code /common/*} — the console and its assets.</li>
 * </ul>
 *
 * <p>Like the exchange's, the control endpoints take query parameters with an empty body (so no JSON
 * parser is needed) and are unauthenticated, hence the config switch — see docs/DESIGN.md §7.
 */
public final class BrokerWebServer implements AutoCloseable {

    private final HttpServer server;
    private final BrokerConsoleService console;
    private final boolean controlsEnabled;

    public BrokerWebServer(String host, int port, BrokerConsoleService console, boolean controlsEnabled)
            throws IOException {
        this.console = console;
        this.controlsEnabled = controlsEnabled;
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/config", this::handleConfig);
        server.createContext("/api/accounts", this::handleAccounts);
        server.createContext("/api/lastsale", this::handleLastSale);
        server.createContext("/api/pnl", this::handlePnl);
        if (controlsEnabled) {
            server.createContext("/api/trading/start", ex -> handleTrading(ex, true));
            server.createContext("/api/trading/stop", ex -> handleTrading(ex, false));
        }
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

    // --- handlers ---

    private void handleStatus(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/status") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, statusJson(console.status()));
    }

    private void handleConfig(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/config") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, "{\"controlsEnabled\":" + controlsEnabled + "}");
    }

    private void handleAccounts(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/accounts") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, Json.array(console.accounts(), BrokerWebServer::accountJson));
    }

    private void handleLastSale(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/lastsale") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, Json.array(console.lastSales(), BrokerWebServer::lastSaleJson));
    }

    private void handlePnl(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/pnl") || HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, Json.array(console.pnl(), BrokerWebServer::pnlJson));
    }

    private void handleTrading(HttpExchange ex, boolean enabled) throws IOException {
        String path = "/api/trading/" + (enabled ? "start" : "stop");
        if (HttpJson.requireExactPath(ex, path) || HttpJson.requireMethod(ex, "POST")) {
            return;
        }
        HttpJson.sendJson(ex, 200, "{\"tradingEnabled\":" + console.setTrading(enabled) + "}");
    }

    // --- JSON rendering ---

    static String statusJson(BrokerStatus s) {
        return "{\"tradingEnabled\":" + s.tradingEnabled()
                + ",\"exchangeConnected\":" + s.exchangeConnected()
                + ",\"accounts\":" + s.accounts()
                + ",\"uptimeMs\":" + s.uptimeMs()
                + ",\"ordersRouted\":" + s.ordersRouted()
                + ",\"fills\":" + s.fills()
                + ",\"rejects\":" + s.rejects() + "}";
    }

    static String accountJson(BrokerRepository.AccountRow a) {
        return "{\"account\":" + Json.str(a.accountNumber())
                + ",\"ownerName\":" + (a.ownerName() == null ? "null" : Json.str(a.ownerName()))
                + ",\"baseCurrency\":" + Json.str(a.baseCurrency()) + "}";
    }

    static String lastSaleJson(LastSale s) {
        return "{\"symbol\":" + Json.str(s.symbol()) + ",\"price\":" + Json.num(s.price()) + "}";
    }

    static String pnlJson(PnlService.AccountPnl p) {
        return "{\"account\":" + Json.str(p.account())
                + ",\"ownerName\":" + (p.ownerName() == null ? "null" : Json.str(p.ownerName()))
                + ",\"baseline\":" + Json.num(p.baseline())
                + ",\"equity\":" + Json.num(p.equity())
                + ",\"realized\":" + Json.num(p.realized())
                + ",\"unrealized\":" + Json.num(p.unrealized())
                + ",\"relative\":" + Json.num(p.relative())
                + ",\"tradeCount\":" + p.tradeCount()
                + ",\"unpricedHoldings\":" + p.unpricedHoldings()
                + ",\"truncated\":" + p.truncated()
                + ",\"points\":" + Json.array(p.points(), BrokerWebServer::pointJson) + "}";
    }

    private static String pointJson(PnlPoint point) {
        return "{\"n\":" + point.tradeCount()
                + ",\"ts\":" + point.ts()
                + ",\"realized\":" + Json.num(point.realized())
                + ",\"unrealized\":" + Json.num(point.unrealized())
                + ",\"relative\":" + Json.num(point.relative()) + "}";
    }
}
