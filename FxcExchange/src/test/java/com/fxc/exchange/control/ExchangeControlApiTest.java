package com.fxc.exchange.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Initiator;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

/**
 * The exchange console's control and status endpoints, end to end against a live exchange
 * (FxcExchange/docs/stories/002).
 *
 * <p>The assertion that matters most is that clearing the book sends every affected broker a
 * {@code CANCELED} ExecutionReport over FIX. Without it a mass cancel would leave each broker's OMS
 * believing its orders are still live — a silent state divergence no HTTP-level check would catch,
 * so this test drives real FIX orders rather than calling the matching service directly.
 */
class ExchangeControlApiTest {

    /** Collects execution reports on the broker (initiator) side. */
    private static final class BrokerClient extends MessageCracker implements Application {
        final CountDownLatch logons = new CountDownLatch(1);
        final List<quickfix.fix44.ExecutionReport> execReports = new CopyOnWriteArrayList<>();

        @Override public void onCreate(SessionID id) { }
        @Override public void onLogon(SessionID id) { logons.countDown(); }
        @Override public void onLogout(SessionID id) { }
        @Override public void toAdmin(Message m, SessionID id) { }
        @Override public void fromAdmin(Message m, SessionID id) { }
        @Override public void toApp(Message m, SessionID id) { }

        @Override
        public void fromApp(Message m, SessionID id)
                throws quickfix.FieldNotFound, quickfix.IncorrectTagValue, quickfix.UnsupportedMessageType {
            crack(m, id);
        }

        public void onMessage(quickfix.fix44.ExecutionReport r, SessionID id) {
            execReports.add(r);
        }
    }

    @Test
    void controlsHaltResumeAndClearTheBookReportingCancelsToBrokers(@TempDir java.nio.file.Path workDir)
            throws Exception {
        int fixPort = freePort();

        try (ExchangeServer server = ExchangeServer.start(
                acceptorSettings(fixPort), "fxc-exchange-control-it", 47571, workDir.toString(),
                InstrumentCatalog.defaults(), null, 0, 0, 0, true)) {

            int http = server.feedService().httpPort();
            HttpClient web = HttpClient.newHttpClient();
            BrokerClient client = new BrokerClient();
            Initiator initiator = new SocketInitiator(client, new MemoryStoreFactory(),
                    initiatorSettings(fixPort), new SLF4JLogFactory(initiatorSettings(fixPort)),
                    new DefaultMessageFactory());
            initiator.start();
            try {
                assertTrue(client.logons.await(15, TimeUnit.SECONDS), "broker session should log on");
                SessionID broker = new SessionID("FIX.4.4", "BROKER1", "EXCHANGE");

                // --- status: open market, listed symbols, no depth yet ---
                String status = body(web, http, "/api/status");
                assertTrue(status.contains("\"marketState\":\"OPEN\""), status);
                assertTrue(status.contains("\"symbol\":\"ARVX\""), status);
                assertTrue(status.contains("\"restingOrders\":0"), status);
                assertTrue(status.contains("\"tradesPerSec\":"), status);
                assertTrue(status.contains("\"wsClients\":0"), status);

                // --- config advertises that controls are live ---
                assertTrue(body(web, http, "/api/config").contains("\"controlsEnabled\":true"));

                // --- rest two orders, confirm they show up over REST ---
                Session.sendToTarget(limit("C-B1", Side.BUY, "ARVX", "42.00", 100), broker);
                Session.sendToTarget(limit("C-S1", Side.SELL, "ARVX", "42.90", 150), broker);
                waitUntil(() -> body(web, http, "/api/status").contains("\"restingOrders\":2"), 8000);

                // Prices are compared numerically: an order's price scale is whatever came off the
                // FIX wire, so asserting the literal "42.00" would pin an unrelated quirk.
                String book = body(web, http, "/api/book?symbol=ARVX");
                assertEquals(42.00, firstNumber(book, "bids", "price"), 1e-9, book);
                assertEquals(100.0, firstNumber(book, "bids", "size"), 1e-9, book);
                assertEquals(42.90, firstNumber(book, "asks", "price"), 1e-9, book);
                assertEquals(150.0, firstNumber(book, "asks", "size"), 1e-9, book);

                // --- halt: new orders are rejected, and the reason says why ---
                assertTrue(post(web, http, "/api/session/halt").contains("\"marketState\":\"HALTED\""));
                assertTrue(body(web, http, "/api/status").contains("\"marketState\":\"HALTED\""));

                Session.sendToTarget(limit("C-B2", Side.BUY, "ARVX", "41.00", 100), broker);
                waitUntil(() -> rejectFor(client, "C-B2") != null, 8000);
                quickfix.fix44.ExecutionReport reject = rejectFor(client, "C-B2");
                assertTrue(reject != null, "halted market should reject the order");
                assertTrue(reject.getString(Text.FIELD).contains("trading halted"),
                        "reject text should name the halt: " + reject.getString(Text.FIELD));

                // --- resume: orders flow again ---
                assertTrue(post(web, http, "/api/session/open").contains("\"marketState\":\"OPEN\""));
                Session.sendToTarget(limit("C-B3", Side.BUY, "ARVX", "41.50", 100), broker);
                waitUntil(() -> ackFor(client, "C-B3"), 8000);
                assertTrue(ackFor(client, "C-B3"), "resumed market should accept orders");

                // --- clear the book: three resting orders cancelled and reported back over FIX ---
                String cleared = post(web, http, "/api/book/clear?symbol=ARVX");
                assertTrue(cleared.contains("\"cancelled\":3"), "expected 3 cancels, got: " + cleared);
                assertTrue(cleared.contains("\"symbol\":\"ARVX\""), cleared);

                waitUntil(() -> cancelledFor(client, "C-B1") && cancelledFor(client, "C-S1")
                        && cancelledFor(client, "C-B3"), 8000);
                assertTrue(cancelledFor(client, "C-B1"), "broker must be told C-B1 was cancelled");
                assertTrue(cancelledFor(client, "C-S1"), "broker must be told C-S1 was cancelled");
                assertTrue(cancelledFor(client, "C-B3"), "broker must be told C-B3 was cancelled");

                String afterClear = body(web, http, "/api/book?symbol=ARVX");
                assertTrue(afterClear.contains("\"bids\":[]") && afterClear.contains("\"asks\":[]"), afterClear);
                assertTrue(body(web, http, "/api/status").contains("\"restingOrders\":0"));

                // --- per-symbol halt leaves other symbols trading ---
                assertTrue(post(web, http, "/api/session/halt?symbol=ARVX").contains("\"symbol\":\"ARVX\""));
                String perSymbol = body(web, http, "/api/status");
                assertTrue(perSymbol.contains("\"marketState\":\"OPEN\""), "market itself stays open");
                assertTrue(perSymbol.contains("\"symbol\":\"ARVX\",\"state\":\"HALTED\""), perSymbol);
                assertTrue(perSymbol.contains("\"symbol\":\"BLTN\",\"state\":\"OPEN\""), perSymbol);

                Session.sendToTarget(limit("C-B4", Side.BUY, "ARVX", "41.00", 100), broker);
                waitUntil(() -> rejectFor(client, "C-B4") != null, 8000);
                assertTrue(rejectFor(client, "C-B4") != null, "halted symbol should reject");

                Session.sendToTarget(limit("C-B5", Side.BUY, "BLTN", "10.00", 100), broker);
                waitUntil(() -> ackFor(client, "C-B5"), 8000);
                assertTrue(ackFor(client, "C-B5"), "an open symbol should still accept orders");

                // --- clear with no symbol clears every book ---
                assertTrue(post(web, http, "/api/book/clear").contains("\"cancelled\":1"));

                // --- method gating and bad requests ---
                assertEquals(405, get(web, http, "/api/session/halt").statusCode(),
                        "a control endpoint must not act on GET");
                assertEquals(405, postRaw(web, http, "/api/status").statusCode());
                assertEquals(400, get(web, http, "/api/book").statusCode(), "symbol is required");
                assertEquals(404, get(web, http, "/api/book?symbol=NOPE").statusCode());
                assertEquals(400, get(web, http, "/api/candles?symbol=ARVX&granularity=7q").statusCode(),
                        "an unusable granularity is a bad request, not a server fault");
            } finally {
                initiator.stop();
            }
        }
    }

    @Test
    void controlsCanBeDisabledLeavingAReadOnlyConsole(@TempDir java.nio.file.Path workDir) throws Exception {
        try (ExchangeServer server = ExchangeServer.start(
                acceptorSettings(freePort()), "fxc-exchange-readonly-it", 47572, workDir.toString(),
                InstrumentCatalog.defaults(), null, 0, 0, 0, false)) {

            int http = server.feedService().httpPort();
            HttpClient web = HttpClient.newHttpClient();

            // Read paths stay available...
            assertEquals(200, get(web, http, "/api/status").statusCode());
            assertEquals(200, get(web, http, "/api/book?symbol=ARVX").statusCode());
            assertTrue(body(web, http, "/api/config").contains("\"controlsEnabled\":false"));

            // ...but the mutating endpoints are not registered at all.
            assertEquals(404, postRaw(web, http, "/api/session/halt").statusCode());
            assertEquals(404, postRaw(web, http, "/api/book/clear").statusCode());
            assertFalse(body(web, http, "/api/status").contains("HALTED"),
                    "no request should have been able to halt the market");
        }
    }

    // --- FIX helpers ---

    private static boolean ackFor(BrokerClient client, String clOrdId) {
        return client.execReports.stream().anyMatch(r -> is(r, clOrdId, ExecType.NEW));
    }

    private static boolean cancelledFor(BrokerClient client, String clOrdId) {
        return client.execReports.stream().anyMatch(r -> is(r, clOrdId, ExecType.CANCELED));
    }

    private static quickfix.fix44.ExecutionReport rejectFor(BrokerClient client, String clOrdId) {
        return client.execReports.stream()
                .filter(r -> is(r, clOrdId, ExecType.REJECTED))
                .findFirst().orElse(null);
    }

    private static boolean is(quickfix.fix44.ExecutionReport r, String clOrdId, char execType) {
        try {
            return r.getString(ClOrdID.FIELD).equals(clOrdId) && r.getExecType().getValue() == execType;
        } catch (quickfix.FieldNotFound e) {
            return false;
        }
    }

    private static NewOrderSingle limit(String clOrdId, char side, String symbol, String price, int qty) {
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId), new Side(side), new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(qty));
        order.set(new Price(Double.parseDouble(price)));
        return order;
    }

    // --- HTTP helpers ---

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

    /** Pull {@code field} out of the first object in the named JSON array, as a double. */
    private static double firstNumber(String json, String array, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + array + "\":\\[\\{[^}]*\"" + field + "\":(-?[0-9.]+)")
                .matcher(json);
        assertTrue(m.find(), "no " + array + "[0]." + field + " in " + json);
        return Double.parseDouble(m.group(1));
    }

    // --- misc helpers ---

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
                HeartBtInt=10
                SocketAcceptPort=%d

                [SESSION]
                TargetCompID=BROKER1
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }

    private static SessionSettings initiatorSettings(int port) throws Exception {
        String cfg = """
                [DEFAULT]
                ConnectionType=initiator
                BeginString=FIX.4.4
                TargetCompID=EXCHANGE
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=10
                ReconnectInterval=1
                SocketConnectHost=127.0.0.1
                SocketConnectPort=%d

                [SESSION]
                SenderCompID=BROKER1
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
