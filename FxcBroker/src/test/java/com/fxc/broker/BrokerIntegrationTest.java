package com.fxc.broker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.ofx.OfxCodec;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestTransaction;
import com.webcohesion.ofx4j.domain.data.investment.accounts.InvestmentAccountDetails;
import com.webcohesion.ofx4j.domain.data.investment.positions.BasePosition;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequest;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementRequestTransaction;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponse;
import com.webcohesion.ofx4j.domain.data.investment.statements.InvestmentStatementResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequest;
import com.webcohesion.ofx4j.domain.data.signon.SignonRequestMessageSet;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

/**
 * Phase 2 exit criteria (PLAN §Phase 2): drive OFX signon → order → fill → statement against a
 * <b>live FxcExchange</b>, for both an FX pair and an equity. A separate BROKER2 FIX client seeds
 * contra liquidity so the broker's routed orders actually fill on the exchange.
 */
class BrokerIntegrationTest {

    /** Minimal FIX client that rests contra liquidity on the exchange as BROKER2. */
    private static final class LiquidityClient implements Application, AutoCloseable {
        private final Initiator initiator;
        private final CountDownLatch logon = new CountDownLatch(1);
        private volatile SessionID session;

        LiquidityClient(SessionSettings settings) throws Exception {
            this.initiator = new SocketInitiator(this, new MemoryStoreFactory(), settings,
                    new SLF4JLogFactory(settings), new DefaultMessageFactory());
        }

        void start() throws Exception { initiator.start(); }
        boolean awaitLogon() throws InterruptedException { return logon.await(15, TimeUnit.SECONDS); }

        void restSell(String symbol, String price, int qty) throws Exception {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID("LQ-" + symbol.replace("/", "") + "-" + qty),
                    new quickfix.field.Side(quickfix.field.Side.SELL),
                    new TransactTime(), new OrdType(OrdType.LIMIT));
            order.set(new Symbol(symbol));
            order.set(new OrderQty(qty));
            order.set(new Price(Double.parseDouble(price)));
            Session.sendToTarget(order, session);
        }

        @Override public void onCreate(SessionID id) { this.session = id; }
        @Override public void onLogon(SessionID id) { this.session = id; logon.countDown(); }
        @Override public void onLogout(SessionID id) { }
        @Override public void toAdmin(Message m, SessionID id) { }
        @Override public void fromAdmin(Message m, SessionID id) { }
        @Override public void toApp(Message m, SessionID id) { }
        @Override public void fromApp(Message m, SessionID id) { }
        @Override public void close() { initiator.stop(); }
    }

    @Test
    void ofxSignonOrderFillStatement_forFxAndEquity(@TempDir java.nio.file.Path workDir) throws Exception {
        int exchangeFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-bit", 47531,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             LiquidityClient liquidity = new LiquidityClient(
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER2", "EXCHANGE"));
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-bit", 47532, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER1", "EXCHANGE"),
                     "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                     accounts -> accounts.seedAccount("000123456", "Dev Investor", "USD",
                             Map.of("USD", new BigDecimal("1000000"))),
                     null)) {

            liquidity.start();
            assertTrue(liquidity.awaitLogon(), "liquidity (BROKER2) should log on to the exchange");
            assertTrue(broker.fixClient().awaitLogon(15, TimeUnit.SECONDS),
                    "broker (BROKER1) should log on to the exchange");

            // Rest contra liquidity so the broker's BUY orders fill.
            liquidity.restSell("EUR/USD", "1.08420", 1000);
            liquidity.restSell("ACME", "42.10", 100);
            Thread.sleep(300); // let the resting orders settle in the book

            String ofxUrl = "http://127.0.0.1:" + broker.ofxPort() + "/ofx";

            // --- Order 1: BUY EUR/USD (FX) ---
            ResponseEnvelope fxOrderResp = post(ofxUrl, orderRequest(
                    "ORD-FX", "000123456", fxSecId("EUR/USD"), "BUY", 1000.0, "LIMIT", 1.08420));
            assertNotNull(fxOrderResp.getSignonResponse());

            // --- Order 2: BUY ACME (equity) ---
            post(ofxUrl, orderRequest(
                    "ORD-EQ", "000123456", tickerSecId("ACME"), "BUY", 100.0, "LIMIT", 42.10));

            // --- Poll the statement until both positions appear (fills are async over FIX) ---
            waitUntil(() -> {
                try {
                    InvestmentStatementResponse stmt = statement(post(ofxUrl, statementRequest("000123456")));
                    return stmt != null && hasPosition(stmt, "FX:EUR") && hasPosition(stmt, "ACME");
                } catch (Exception e) {
                    return false;
                }
            }, 10_000);

            InvestmentStatementResponse stmt =
                    statement(post(ofxUrl, statementRequest("000123456")));
            assertNotNull(stmt, "statement response");
            assertTrue(hasPosition(stmt, "FX:EUR"),
                    "FX position (EUR balance from buying EUR/USD) should show on the statement");
            assertTrue(hasPosition(stmt, "ACME"),
                    "equity position (ACME shares) should show on the statement");
        }
    }

    // --- OFX request builders ---

    private static RequestEnvelope withSignon(RequestEnvelope env, TreeSet<RequestMessageSet> sets) {
        SignonRequest signon = new SignonRequest();
        signon.setUserId("investor");
        signon.setPassword("secret");
        signon.setTimestamp(new Date());
        signon.setLanguage("ENG");
        signon.setApplicationId("FXC");
        signon.setApplicationVersion("0100");
        SignonRequestMessageSet signonSet = new SignonRequestMessageSet();
        signonSet.setSignonRequest(signon);
        sets.add(signonSet);
        env.setMessageSets(sets);
        return env;
    }

    private static RequestEnvelope orderRequest(String clOrdId, String account, SecurityId secId,
                                                String side, double units, String type, double limitPrice) {
        FxcOrderRequest order = new FxcOrderRequest();
        order.setAccountId(account);
        order.setSecurityId(secId);
        order.setSide(side);
        order.setUnits(units);
        order.setOrderType(type);
        order.setLimitPrice(limitPrice);

        FxcOrderRequestTransaction txn = new FxcOrderRequestTransaction();
        txn.setUID(clOrdId);
        txn.setWrappedMessage(order);

        FxcOrderRequestMessageSet set = new FxcOrderRequestMessageSet();
        set.setOrderRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID(clOrdId);
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        return withSignon(env, sets);
    }

    private static RequestEnvelope statementRequest(String account) {
        InvestmentAccountDetails acct = new InvestmentAccountDetails();
        acct.setBrokerId("FXC-BROKER");
        acct.setAccountNumber(account);
        InvestmentStatementRequest stmt = new InvestmentStatementRequest();
        stmt.setAccount(acct);
        com.webcohesion.ofx4j.domain.data.investment.statements.IncludePosition incPos =
                new com.webcohesion.ofx4j.domain.data.investment.statements.IncludePosition();
        incPos.setIncludePositions(true);
        stmt.setIncludePosition(incPos);
        stmt.setIncludeOpenOrders(false);
        stmt.setIncludeBalance(true);
        InvestmentStatementRequestTransaction txn = new InvestmentStatementRequestTransaction();
        txn.setUID("STMT-" + account);
        txn.setMessage(stmt);
        InvestmentStatementRequestMessageSet set = new InvestmentStatementRequestMessageSet();
        set.setStatementRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID("STMT-" + account);
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        return withSignon(env, sets);
    }

    private static SecurityId fxSecId(String pair) {
        SecurityId id = new SecurityId();
        id.setUniqueId("FX:" + pair);
        id.setUniqueIdType("FXC");
        return id;
    }

    private static SecurityId tickerSecId(String ticker) {
        SecurityId id = new SecurityId();
        id.setUniqueId(ticker);
        id.setUniqueIdType("TICKER");
        return id;
    }

    // --- OFX response parsing ---

    private static InvestmentStatementResponse statement(ResponseEnvelope response) {
        for (ResponseMessageSet set : response.getMessageSets()) {
            if (set instanceof InvestmentStatementResponseMessageSet stmt
                    && stmt.getStatementResponse() != null) {
                return stmt.getStatementResponse().getMessage();
            }
        }
        return null;
    }

    private static boolean hasPosition(InvestmentStatementResponse stmt, String uniqueId) {
        if (stmt.getPositionList() == null || stmt.getPositionList().getPositions() == null) {
            return false;
        }
        List<BasePosition> positions = new ArrayList<>(stmt.getPositionList().getPositions());
        return positions.stream().anyMatch(p -> p.getSecurityId() != null
                && uniqueId.equals(p.getSecurityId().getUniqueId())
                && p.getUnits() != null && p.getUnits() > 0);
    }

    // --- HTTP + helpers ---

    private static ResponseEnvelope post(String url, RequestEnvelope request) throws Exception {
        byte[] body = OfxCodec.marshalRequest(request);
        HttpResponse<byte[]> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", OfxCodec.CONTENT_TYPE)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("OFX POST failed " + resp.statusCode()
                    + ": " + new String(resp.body(), StandardCharsets.UTF_8));
        }
        return OfxCodec.unmarshalResponse(new ByteArrayInputStream(resp.body()));
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
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

                [SESSION]
                TargetCompID=BROKER2
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
