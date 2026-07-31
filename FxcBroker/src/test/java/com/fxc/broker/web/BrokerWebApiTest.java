package com.fxc.broker.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.BrokerServer;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Side;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.broker.oms.OrderResult;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionSettings;

/**
 * The broker console's REST surface against a live broker wired to a live exchange
 * (FxcBroker/docs/stories/002): status, accounts, the last-sale ticker, the P&amp;L series, and the
 * start/stop trading control.
 *
 * <p>Runs against a real exchange rather than a stub so the market-data subscription that feeds both
 * the ticker and the P&amp;L marks is genuinely exercised — it is established by a FIX logon that a
 * stub would not perform.
 */
class BrokerWebApiTest {

    private static final String ACCOUNT = "000123456";

    @Test
    void servesStatusAccountsTickerAndPnlAndGatesTrading(@TempDir java.nio.file.Path workDir)
            throws Exception {
        int exchangeFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-webit", 47591,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-webit", 47592, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER1", "EXCHANGE"),
                     "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                     accounts -> {
                         accounts.seedAccount(ACCOUNT, "Dev Investor", "USD",
                                 Map.of("USD", new BigDecimal("1000000")));
                         accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("1000"), new BigDecimal("42.00"));
                     },
                     null, null, 0, 0, true)) {

            assertTrue(broker.fixClient().awaitLogon(15, TimeUnit.SECONDS),
                    "broker should log on to the exchange");
            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            // --- status ---
            waitUntil(() -> body(client, web, "/api/status").contains("\"exchangeConnected\":true"), 8000);
            String status = body(client, web, "/api/status");
            assertTrue(status.contains("\"tradingEnabled\":true"), status);
            assertTrue(status.contains("\"exchangeConnected\":true"), status);
            assertTrue(status.contains("\"accounts\":1"), status);
            assertTrue(status.contains("\"ordersRouted\":0"), status);
            assertTrue(status.contains("\"fills\":0"), status);

            String config = body(client, web, "/api/config");
            assertTrue(config.contains("\"controlsEnabled\":true"), config);
            assertTrue(config.contains("\"accountsEnabled\":true"), config);

            // --- accounts ---
            String accounts = body(client, web, "/api/accounts");
            assertTrue(accounts.contains("\"account\":\"" + ACCOUNT + "\""), accounts);
            assertTrue(accounts.contains("\"baseCurrency\":\"USD\""), accounts);

            // --- P&L: baseline is cash plus the seeded shares at cost, curve anchored at zero ---
            String pnl = body(client, web, "/api/pnl");
            assertTrue(pnl.contains("\"account\":\"" + ACCOUNT + "\""), pnl);
            assertTrue(pnl.contains("\"baseline\":1042000.00"), "1,000,000 cash + 1,000 ACME at 42.00: " + pnl);
            assertTrue(pnl.contains("\"relative\":0.00"), pnl);
            assertTrue(pnl.contains("\"tradeCount\":0"), pnl);
            assertTrue(pnl.contains("\"unpricedHoldings\":0"), pnl);
            assertTrue(pnl.contains("\"windowMs\":900000"),
                    "the console needs the window to label its axis: " + pnl);
            assertTrue(pnl.contains("\"dropped\":0"), pnl);
            assertTrue(pnl.contains("\"plotted\":true"), pnl);
            assertTrue(pnl.contains("\"n\":0"), "the curve should be anchored at zero trades: " + pnl);

            // --- ticker: empty until the exchange reports a trade, then populated ---
            assertEquals("[]", body(client, web, "/api/lastsale"),
                    "nothing has traded yet, so the ticker is empty rather than fabricated");

            exchange.matchingService().submit(new com.fxc.exchange.book.NewOrder(
                    "seed-s", "BROKER2", "ACME", com.fxc.exchange.book.Side.SELL,
                    com.fxc.exchange.book.OrderType.LIMIT, new BigDecimal("42.50"), new BigDecimal("10")));
            exchange.matchingService().submit(new com.fxc.exchange.book.NewOrder(
                    "seed-b", "BROKER2", "ACME", com.fxc.exchange.book.Side.BUY,
                    com.fxc.exchange.book.OrderType.LIMIT, new BigDecimal("42.50"), new BigDecimal("10")));

            waitUntil(() -> body(client, web, "/api/lastsale").contains("\"symbol\":\"ACME\""), 8000);
            String ticker = body(client, web, "/api/lastsale");
            assertTrue(ticker.contains("\"symbol\":\"ACME\""), ticker);
            assertTrue(ticker.contains("\"price\":42.5"), ticker);

            // With ACME marked at 42.50 the 1,000 seeded shares revalue: +500 unrealized, no realized.
            waitUntil(() -> body(client, web, "/api/pnl").contains("\"relative\":500.00"), 8000);
            String revalued = body(client, web, "/api/pnl");
            assertTrue(revalued.contains("\"relative\":500.00"), revalued);
            assertTrue(revalued.contains("\"unrealized\":500.00"), revalued);
            assertTrue(revalued.contains("\"realized\":0.00"), revalued);

            // --- stop trading: the OMS refuses new orders while the exchange stays open ---
            assertTrue(post(client, web, "/api/trading/stop").contains("\"tradingEnabled\":false"));
            assertTrue(body(client, web, "/api/status").contains("\"tradingEnabled\":false"));

            OrderResult stopped = broker.omsService().submit(ACCOUNT, "WEB-1", "ACME",
                    Side.BUY, OrderType.LIMIT, new BigDecimal("42.00"), new BigDecimal("1"));
            assertFalse(stopped.accepted(), "a stopped broker must not route orders");
            assertTrue(stopped.reason().contains("trading stopped by operator"),
                    "reject reason should name the operator stop: " + stopped.reason());
            assertTrue(body(client, web, "/api/status").contains("\"rejects\":1"));

            // The exchange itself is unaffected — this is a broker-side gate.
            assertTrue(exchange.matchingService().submit(new com.fxc.exchange.book.NewOrder(
                    "still-open", "BROKER2", "GLOBEX", com.fxc.exchange.book.Side.BUY,
                    com.fxc.exchange.book.OrderType.LIMIT, new BigDecimal("10.00"),
                    new BigDecimal("5"))).accepted());

            // --- start trading: orders route again ---
            assertTrue(post(client, web, "/api/trading/start").contains("\"tradingEnabled\":true"));
            OrderResult resumed = broker.omsService().submit(ACCOUNT, "WEB-2", "ACME",
                    Side.BUY, OrderType.LIMIT, new BigDecimal("42.00"), new BigDecimal("1"));
            assertTrue(resumed.accepted(), "a resumed broker should route orders again");

            // --- method gating and unknown paths ---
            assertEquals(405, get(client, web, "/api/trading/stop").statusCode());
            assertEquals(405, postRaw(client, web, "/api/status").statusCode());
            assertEquals(404, get(client, web, "/api/nope").statusCode());
        }
    }

    @Test
    void controlsCanBeDisabledLeavingAReadOnlyConsole(@TempDir java.nio.file.Path workDir) throws Exception {
        try (BrokerServer broker = BrokerServer.start(
                "fxc-broker-webro", 47593, workDir.toString(),
                FixSettingsFactory.initiator("127.0.0.1", freePort(), "BROKER1", "EXCHANGE"),
                "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                accounts -> accounts.seedAccount(ACCOUNT, "Dev Investor", "USD",
                        Map.of("USD", new BigDecimal("1000"))),
                null, null, 0, 0, false)) {

            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(200, get(client, web, "/api/status").statusCode());
            assertEquals(200, get(client, web, "/api/pnl").statusCode());
            assertTrue(body(client, web, "/api/config").contains("\"controlsEnabled\":false"));

            assertEquals(404, postRaw(client, web, "/api/trading/stop").statusCode());
            assertEquals(404, postRaw(client, web, "/api/trading/start").statusCode());
            assertTrue(body(client, web, "/api/status").contains("\"tradingEnabled\":true"),
                    "no request should have been able to stop trading");
        }
    }

    @Test
    void internalAccountsAreListedButKeptOffTheConsole(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // The issuer and market makers sit below 100 and hold the whole float. /api/accounts is the raw
        // list and still shows them — it is what the account-opening flow reads — but /api/pnl is the
        // console's view and must not, or mark-to-market on the float buries every customer's P&L.
        try (BrokerServer broker = BrokerServer.start(
                "fxc-broker-webinternal", 47598, workDir.toString(),
                FixSettingsFactory.initiator("127.0.0.1", freePort(), "BROKER1", "EXCHANGE"),
                "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                accounts -> {
                    accounts.seedAccount("000000001", "Market Maker", "USD",
                            Map.of("USD", new BigDecimal("1000000")));
                    accounts.seedShares("000000001", "ACME", new BigDecimal("500000"),
                            new BigDecimal("42.00"));
                    accounts.seedAccount("000100001", "Investor locust-0", "USD",
                            Map.of("USD", new BigDecimal("1000000")));
                },
                null, null, 0, 0, true)) {

            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            String accounts = body(client, web, "/api/accounts");
            assertTrue(accounts.contains("000000001"), "the raw list keeps them: " + accounts);
            assertTrue(accounts.contains("000100001"), accounts);

            String pnl = body(client, web, "/api/pnl");
            assertTrue(pnl.contains("000100001"), "the customer is on the console: " + pnl);
            assertFalse(pnl.contains("000000001"), "the market maker is not: " + pnl);
        }
    }

    @Test
    void opensAccountsForAgentsIdempotentlyPerClientId(@TempDir java.nio.file.Path workDir)
            throws Exception {
        try (BrokerServer broker = BrokerServer.start(
                "fxc-broker-webopen", 47596, workDir.toString(),
                FixSettingsFactory.initiator("127.0.0.1", freePort(), "BROKER1", "EXCHANGE"),
                "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                accounts -> {
                    accounts.configureOpening(new com.fxc.broker.account.AccountOpeningPolicy(
                            true, "USD", new BigDecimal("1000000"), "ACME", new BigDecimal("1000"),
                            new BigDecimal("42.00"), 100_000L, 9));
                    accounts.seedAccount(ACCOUNT, "Dev Investor", "USD",
                            Map.of("USD", new BigDecimal("1000")));
                },
                null, null, 0, 0, true)) {

            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> opened = postRaw(client, web, "/api/accounts?clientId=locust-0");
            assertEquals(201, opened.statusCode(), opened.body());
            assertTrue(opened.body().contains("\"opened\":true"), opened.body());

            HttpResponse<String> again = postRaw(client, web, "/api/accounts?clientId=locust-0");
            assertEquals(200, again.statusCode(), "the second call found it rather than minting one");
            assertTrue(again.body().contains("\"opened\":false"), again.body());
            // Same account both times: a respawning agent must not fragment its P&L.
            String account = opened.body().replaceAll(".*\"account\":\"([0-9]+)\".*", "$1");
            assertTrue(again.body().contains("\"account\":\"" + account + "\""), again.body());

            // A second client gets its own, and both show up in the list and the P&L table.
            assertEquals(201, postRaw(client, web, "/api/accounts?clientId=locust-1").statusCode());
            String accounts = body(client, web, "/api/accounts");
            assertTrue(accounts.contains(account), accounts);
            assertTrue(body(client, web, "/api/status").contains("\"accounts\":3"),
                    "the seeded dev account plus the two opened ones");
            // Opened mid-session, so its curve is anchored rather than starting at its first fill.
            assertTrue(body(client, web, "/api/pnl").contains("\"account\":\"" + account + "\""));

            // A missing client id is the caller's error, not a server fault.
            assertEquals(400, postRaw(client, web, "/api/accounts").statusCode());
            assertEquals(400, postRaw(client, web, "/api/accounts?clientId=").statusCode());
        }
    }

    @Test
    void accountOpeningCanBeDisabledWithoutDisablingTheConsole(@TempDir java.nio.file.Path workDir)
            throws Exception {
        try (BrokerServer broker = BrokerServer.start(
                "fxc-broker-webnoopen", 47597, workDir.toString(),
                FixSettingsFactory.initiator("127.0.0.1", freePort(), "BROKER1", "EXCHANGE"),
                "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                accounts -> accounts.seedAccount(ACCOUNT, "Dev Investor", "USD",
                        Map.of("USD", new BigDecimal("1000"))),
                null, null, 0, 0, true, com.fxc.broker.ofx.OfxHttpServer.DEFAULT_THREADS,
                com.fxc.broker.pnl.PnlSettings.defaults(), false)) {

            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            assertEquals(404, postRaw(client, web, "/api/accounts?clientId=locust-0").statusCode());
            assertTrue(body(client, web, "/api/config").contains("\"accountsEnabled\":false"));
            // The operator controls are a separate switch and are still live.
            assertTrue(body(client, web, "/api/config").contains("\"controlsEnabled\":true"));
            assertEquals(200, postRaw(client, web, "/api/trading/stop").statusCode());
            // Listing still works: disabling opening does not hide the accounts that exist.
            assertTrue(body(client, web, "/api/accounts").contains(ACCOUNT));
        }
    }

    @Test
    void servesTheConsolePageAndItsAssets(@TempDir java.nio.file.Path workDir) throws Exception {
        try (BrokerServer broker = BrokerServer.start(
                "fxc-broker-webui", 47594, workDir.toString(),
                FixSettingsFactory.initiator("127.0.0.1", freePort(), "BROKER1", "EXCHANGE"),
                "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                accounts -> accounts.seedAccount(ACCOUNT, "Dev Investor", "USD",
                        Map.of("USD", new BigDecimal("1000"))),
                null, null, 0, 0, true)) {

            int web = broker.webServer().boundPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> page = get(client, web, "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").contains("text/html"));
            assertTrue(page.body().contains("/common/vendor/d3.v7.min.js"), "console should load D3");
            assertTrue(page.body().contains("/assets/broker.js"));
            assertTrue(page.body().contains("fxc-menu"), "console should have the hover control menu");

            HttpResponse<String> script = get(client, web, "/assets/broker.js");
            assertEquals(200, script.statusCode());
            assertTrue(script.body().contains("/api/pnl"));
            assertTrue(script.body().contains("/api/lastsale"));

            // Shared assets resolve out of the fxc-common jar, not a per-component copy.
            assertEquals(200, get(client, web, "/common/fxc.css").statusCode());
            assertEquals(200, get(client, web, "/common/vendor/d3.v7.min.js").statusCode());
            assertEquals(404, get(client, web, "/common/nope.js").statusCode());
        }
    }

    // --- helpers ---

    private static URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static HttpResponse<String> get(HttpClient client, int port, String path) {
        return send(client, HttpRequest.newBuilder(uri(port, path)).GET().build());
    }

    private static HttpResponse<String> postRaw(HttpClient client, int port, String path) {
        return send(client, HttpRequest.newBuilder(uri(port, path))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private static String body(HttpClient client, int port, String path) {
        HttpResponse<String> res = get(client, port, path);
        assertEquals(200, res.statusCode(), "GET " + path + " -> " + res.body());
        return res.body();
    }

    private static String post(HttpClient client, int port, String path) {
        HttpResponse<String> res = postRaw(client, port, path);
        assertEquals(200, res.statusCode(), "POST " + path + " -> " + res.body());
        return res.body();
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(request.method() + " " + request.uri() + " failed", e);
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SessionSettings exchangeAcceptorSettings(int port) throws Exception {
        String cfg = """
                [DEFAULT]
                ConnectionType=acceptor
                BeginString=FIX.4.4
                SenderCompID=EXCHANGE
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=10
                SocketAcceptPort=%d

                [SESSION]
                TargetCompID=BROKER1
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
