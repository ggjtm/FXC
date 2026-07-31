package com.fxc.broker.web;

import com.fxc.broker.account.AccountService;
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
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The broker's monitor/controller console and its REST surface (docs/DESIGN.md §6,
 * FxcBroker/docs/stories/002). A second JDK {@link HttpServer} on its own port, leaving the
 * POST-only OFX endpoint untouched — the two have nothing in common but a transport, and the console
 * must be disableable without affecting OFX.
 *
 * <ul>
 *   <li>{@code GET /api/status} — trading switch, exchange link, counters, uptime;</li>
 *   <li>{@code GET /api/config} — whether controls and account opening are enabled;</li>
 *   <li>{@code GET /api/accounts} — the managed accounts;</li>
 *   <li>{@code POST /api/accounts?clientId=…} — open an account for an agent (docs/stories/004);</li>
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
    private final boolean accountsEnabled;

    public BrokerWebServer(String host, int port, BrokerConsoleService console, boolean controlsEnabled)
            throws IOException {
        this(host, port, console, controlsEnabled, true);
    }

    public BrokerWebServer(String host, int port, BrokerConsoleService console, boolean controlsEnabled,
                           boolean accountsEnabled) throws IOException {
        this.console = console;
        this.controlsEnabled = controlsEnabled;
        this.accountsEnabled = accountsEnabled;
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
        HttpJson.sendJson(ex, 200, "{\"controlsEnabled\":" + controlsEnabled
                + ",\"accountsEnabled\":" + accountsEnabled + "}");
    }

    /**
     * {@code GET} lists accounts; {@code POST} opens one for an agent (docs/stories/004).
     *
     * <p>One context, dispatched on method, because both are the same resource. The exact-path guard
     * runs first for the reason {@code fxc-common}'s story 001 records: a prefix context would
     * otherwise swallow paths below it.
     */
    private void handleAccounts(HttpExchange ex) throws IOException {
        if (HttpJson.requireExactPath(ex, "/api/accounts")) {
            return;
        }
        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            handleOpenAccount(ex);
            return;
        }
        if (HttpJson.requireMethod(ex, "GET")) {
            return;
        }
        HttpJson.sendJson(ex, 200, Json.array(console.accounts(), BrokerWebServer::accountJson));
    }

    /**
     * Open (or re-find) an agent's account: {@code POST /api/accounts?clientId=…&ownerName=…}.
     *
     * <p><b>Query parameters, empty body</b> — the same shape the exchange's control POSTs take, and
     * for the same reason: the repo has a JSON writer and deliberately no parser.
     *
     * <p>Gated by its own {@code web.accounts.enabled} rather than {@code web.controls.enabled}:
     * turning the console read-only for an operator should not stop agents from opening accounts, and
     * a broker that must not mint accounts should not have to give up the console's stop-trading
     * control to say so.
     */
    private void handleOpenAccount(HttpExchange ex) throws IOException {
        if (!accountsEnabled) {
            HttpJson.sendError(ex, 404, "account opening is disabled");
            return;
        }
        Map<String, String> query = HttpJson.query(ex.getRequestURI());
        String clientId = HttpJson.optional(query, "clientId");
        if (clientId == null || clientId.isBlank()) {
            HttpJson.sendError(ex, 400, "clientId required");
            return;
        }
        try {
            AccountService.OpenResult result =
                    console.openAccount(clientId, HttpJson.optional(query, "ownerName"));
            HttpJson.sendJson(ex, result.opened() ? 201 : 200,
                    "{\"account\":" + Json.str(result.account())
                            + ",\"opened\":" + result.opened()
                            + ",\"clientId\":" + Json.str(clientId) + "}");
        } catch (IllegalStateException | IllegalArgumentException refused) {
            // The broker declining is not a server fault: say which it was and why.
            HttpJson.sendError(ex, refused instanceof IllegalStateException ? 409 : 400,
                    refused.getMessage());
        }
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
                + ",\"windowMs\":" + p.windowMs()
                + ",\"dropped\":" + p.dropped()
                + ",\"plotted\":" + p.plotted()
                + ",\"groups\":" + Json.array(p.groups(), Json::str)
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
