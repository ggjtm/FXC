package com.fxc.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.exchange.service.SettlementObligation;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
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
import quickfix.Message;
import quickfix.MemoryStoreFactory;
import quickfix.MessageCracker;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.MDEntryType;
import quickfix.field.MDReqID;
import quickfix.field.MarketDepth;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.SubscriptionRequestType;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.MarketDataIncrementalRefresh;
import quickfix.fix44.MarketDataRequest;
import quickfix.fix44.MarketDataSnapshotFullRefresh;
import quickfix.fix44.NewOrderSingle;

/**
 * Phase 1 exit criteria (PLAN §Phase 1): a scripted QuickFIX/J client submits crossing orders in
 * both an FX pair and an equity against a live FxcExchange, and receives fills and market data for
 * each. Also verifies clearing produces settlement obligations for both asset classes.
 */
class ExchangeIntegrationTest {

    /** Collects execution reports and market data on the broker (initiator) side. */
    private static final class BrokerClient extends MessageCracker implements Application {
        final CountDownLatch logons = new CountDownLatch(2);
        final List<quickfix.fix44.ExecutionReport> execReports = new CopyOnWriteArrayList<>();
        final List<MarketDataSnapshotFullRefresh> snapshots = new CopyOnWriteArrayList<>();
        final List<MarketDataIncrementalRefresh> increments = new CopyOnWriteArrayList<>();

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

        public void onMessage(MarketDataSnapshotFullRefresh r, SessionID id) {
            snapshots.add(r);
        }

        public void onMessage(MarketDataIncrementalRefresh r, SessionID id) {
            increments.add(r);
        }
    }

    @Test
    void crossingOrdersInFxAndEquityProduceFillsMarketDataAndObligations(@TempDir java.nio.file.Path workDir)
            throws Exception {
        int port = freePort();

        try (ExchangeServer server = ExchangeServer.start(
                acceptorSettings(port), "fxc-exchange-it", 47521, workDir.toString(),
                InstrumentCatalog.defaults())) {

            BrokerClient client = new BrokerClient();
            Initiator initiator = new SocketInitiator(client, new MemoryStoreFactory(),
                    initiatorSettings(port), new SLF4JLogFactory(initiatorSettings(port)),
                    new DefaultMessageFactory());
            initiator.start();
            try {
                assertTrue(client.logons.await(15, TimeUnit.SECONDS), "both broker sessions should log on");

                SessionID broker1 = new SessionID("FIX.4.4", "BROKER1", "EXCHANGE");
                SessionID broker2 = new SessionID("FIX.4.4", "BROKER2", "EXCHANGE");

                // BROKER1 subscribes to market data for both instruments.
                Session.sendToTarget(marketDataRequest("MDR-FX", "EUR/USD"), broker1);
                Session.sendToTarget(marketDataRequest("MDR-EQ", "ACME"), broker1);
                waitUntil(() -> client.snapshots.size() >= 2, 5000);

                // FX: BROKER1 rests a SELL, BROKER2 crosses with a BUY.
                Session.sendToTarget(limit("FX-S1", Side.SELL, "EUR/USD", "1.08420", 1000), broker1);
                Session.sendToTarget(limit("FX-B1", Side.BUY, "EUR/USD", "1.08420", 1000), broker2);

                // Equity: BROKER1 rests a SELL, BROKER2 crosses with a BUY.
                Session.sendToTarget(limit("EQ-S1", Side.SELL, "ACME", "42.10", 100), broker1);
                Session.sendToTarget(limit("EQ-B1", Side.BUY, "ACME", "42.10", 100), broker2);

                // Expect: each of the 4 orders acked (NEW) + 2 fills per crossing pair = plenty of reports.
                waitUntil(() -> filledFor(client, "FX-B1") && filledFor(client, "FX-S1")
                        && filledFor(client, "EQ-B1") && filledFor(client, "EQ-S1"), 8000);

                // --- fills ---
                assertFilled(client, "FX-B1", 1000);
                assertFilled(client, "FX-S1", 1000);
                assertFilled(client, "EQ-B1", 100);
                assertFilled(client, "EQ-S1", 100);

                // --- market data: snapshot on subscribe + incremental carrying the trade ---
                assertTrue(client.snapshots.size() >= 2, "expected snapshots for both subscriptions");
                waitUntil(() -> hasTradeIncrement(client, "EUR/USD") && hasTradeIncrement(client, "ACME"), 5000);
                assertTrue(hasTradeIncrement(client, "EUR/USD"), "expected an FX trade on the incremental feed");
                assertTrue(hasTradeIncrement(client, "ACME"), "expected an equity trade on the incremental feed");

                // --- clearing: obligations for both asset classes ---
                List<SettlementObligation> obligations = server.clearingService().runCycle(1);
                assertTrue(obligations.stream().anyMatch(o -> o.symbol().equals("EUR/USD")
                                && o.settleStyle().equals("CURRENCY_EXCHANGE")),
                        "expected an FX currency-exchange obligation");
                assertTrue(obligations.stream().anyMatch(o -> o.symbol().equals("ACME")
                                && o.settleStyle().equals("DELIVERY_VERSUS_PAYMENT")),
                        "expected an equity DVP obligation");
                // Two brokers x two symbols = four netted obligations.
                assertEquals(4, obligations.size());
            } finally {
                initiator.stop();
            }
        }
    }


    // --- helpers ---

    private static boolean filledFor(BrokerClient client, String clOrdId) {
        return client.execReports.stream().anyMatch(r -> isFilled(r, clOrdId));
    }

    private static boolean isFilled(quickfix.fix44.ExecutionReport r, String clOrdId) {
        try {
            return r.getString(ClOrdID.FIELD).equals(clOrdId)
                    && r.getOrdStatus().getValue() == quickfix.field.OrdStatus.FILLED;
        } catch (quickfix.FieldNotFound e) {
            return false;
        }
    }

    private static void assertFilled(BrokerClient client, String clOrdId, double qty) {
        quickfix.fix44.ExecutionReport report = client.execReports.stream()
                .filter(r -> isFilled(r, clOrdId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no FILLED report for " + clOrdId));
        try {
            assertEquals(qty, report.getCumQty().getValue(), 1e-9, "cumQty for " + clOrdId);
        } catch (quickfix.FieldNotFound e) {
            throw new AssertionError(e);
        }
    }

    private static boolean hasTradeIncrement(BrokerClient client, String symbol) {
        for (MarketDataIncrementalRefresh inc : client.increments) {
            try {
                int n = inc.getInt(quickfix.field.NoMDEntries.FIELD);
                MarketDataIncrementalRefresh.NoMDEntries entry = new MarketDataIncrementalRefresh.NoMDEntries();
                for (int i = 1; i <= n; i++) {
                    inc.getGroup(i, entry);
                    if (entry.getMDEntryType().getValue() == MDEntryType.TRADE
                            && entry.getString(Symbol.FIELD).equals(symbol)) {
                        return true;
                    }
                }
            } catch (quickfix.FieldNotFound ignored) {
                // skip malformed
            }
        }
        return false;
    }

    private static NewOrderSingle limit(String clOrdId, char side, String symbol, String price, int qty) {
        NewOrderSingle order = new NewOrderSingle(
                new ClOrdID(clOrdId), new Side(side), new TransactTime(), new OrdType(OrdType.LIMIT));
        order.set(new Symbol(symbol));
        order.set(new OrderQty(qty));
        order.set(new Price(Double.parseDouble(price)));
        return order;
    }

    private static MarketDataRequest marketDataRequest(String mdReqId, String symbol) {
        MarketDataRequest req = new MarketDataRequest(
                new MDReqID(mdReqId),
                new SubscriptionRequestType('1'), // 1 = Snapshot + Updates
                new MarketDepth(0));

        MarketDataRequest.NoMDEntryTypes bid = new MarketDataRequest.NoMDEntryTypes();
        bid.set(new MDEntryType(MDEntryType.BID));
        req.addGroup(bid);
        MarketDataRequest.NoMDEntryTypes offer = new MarketDataRequest.NoMDEntryTypes();
        offer.set(new MDEntryType(MDEntryType.OFFER));
        req.addGroup(offer);
        MarketDataRequest.NoMDEntryTypes trade = new MarketDataRequest.NoMDEntryTypes();
        trade.set(new MDEntryType(MDEntryType.TRADE));
        req.addGroup(trade);

        MarketDataRequest.NoRelatedSym sym = new MarketDataRequest.NoRelatedSym();
        sym.set(new Symbol(symbol));
        req.addGroup(sym);
        return req;
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

                [SESSION]
                TargetCompID=BROKER2
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

                [SESSION]
                SenderCompID=BROKER2
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
