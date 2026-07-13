package com.fxc.pub;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.broker.BrokerServer;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Side;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.pub.xmpp.XmppConnectionFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jxmpp.jid.impl.JidCreate;
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
 * Phase 3 exit criteria (PLAN §Phase 3): a fill on FxcExchange appears as a status on the broker's
 * feed, readable via an XMPP (Smack) subscription to the PubSub node. Chains a live FxcExchange +
 * FxcBroker (with its drop-copy leg) + FxcPub, all against the running Tigase; a BROKER2 client
 * seeds contra liquidity.
 *
 * <p>Requires the Tigase container; skips (does not fail the build) when 127.0.0.1:5222 is closed.
 */
class PubIntegrationIT {

    private static final String HOST = "127.0.0.1";
    private static final String DOMAIN = "fxc.local";

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
                    new ClOrdID("LQ-" + symbol + "-" + qty),
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
    void fillOnExchangeBecomesStatusOnBrokerFeedReadViaXmpp(@TempDir java.nio.file.Path workDir) throws Exception {
        assumeTrue(reachable(HOST, 5222), "Tigase not running on " + HOST + ":5222 — skipping");

        int exchangeFixPort = freePort();
        int pubFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-pit", 47531,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             PubServer pub = PubServer.start(
                     "fxc-pub-pit", 47533, workDir.resolve("pub").toString(),
                     HOST, 5222, DOMAIN, "pub-service", "secret",
                     pubDropCopyAcceptorSettings(pubFixPort));
             LiquidityClient liquidity = new LiquidityClient(
                     FixSettingsFactory.initiator(HOST, exchangeFixPort, "BROKER2", "EXCHANGE"));
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-pit", 47532, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator(HOST, exchangeFixPort, "BROKER1", "EXCHANGE"),
                     HOST, 0, "investor", "secret", "FXC-BROKER",
                     accounts -> accounts.seedAccount("000123456", "Dev Investor", "USD",
                             Map.of("USD", new BigDecimal("1000000"))),
                     FixSettingsFactory.initiator(HOST, pubFixPort, "BROKER1", "FXCPUB"))) {

            liquidity.start();
            assertTrue(liquidity.awaitLogon(), "liquidity should log on to the exchange");
            liquidity.restSell("ACME", "42.10", 100);

            // Subscriber reads the broker's feed over XMPP. Wait until FxcPub has created the node
            // (on the broker's drop-copy logon), then subscribe.
            XMPPTCPConnection subscriber = new XmppConnectionFactory(HOST, 5222, DOMAIN).connect("investor", "secret");
            try {
                PubSubManager pubsub = PubSubManager.getInstanceFor(subscriber, JidCreate.bareFrom("pubsub." + DOMAIN));
                String feedNode = "feed-BROKER1";

                LeafNode node = awaitNode(pubsub, feedNode, 15_000);
                CountDownLatch received = new CountDownLatch(1);
                AtomicReference<String> status = new AtomicReference<>();
                node.addItemEventListener(event -> {
                    for (Object item : event.getItems()) {
                        status.set(String.valueOf(item));
                        received.countDown();
                    }
                });
                node.subscribe(subscriber.getUser().asEntityBareJidString());

                // Drive a fill through the broker (OFX path is proven in Phase 2; here we submit
                // directly to the OMS). It routes to the exchange, crosses the resting liquidity,
                // and the resulting fill is drop-copied to FxcPub and published to the feed.
                broker.omsService().submit("000123456", "PUB-EQ", "ACME",
                        Side.BUY, OrderType.LIMIT, new BigDecimal("42.10"), new BigDecimal("100"));

                assertTrue(received.await(15, TimeUnit.SECONDS),
                        "a fill should appear as a status on the broker's feed via XMPP");
                assertTrue(status.get() != null && status.get().contains("FILLED") && status.get().contains("ACME"),
                        "status should render the fill, got: " + status.get());
            } finally {
                subscriber.disconnect();
            }
        }
    }

    private static LeafNode awaitNode(PubSubManager pubsub, String nodeId, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                return pubsub.getLeafNode(nodeId);
            } catch (Exception notYet) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("feed node " + nodeId + " was not created by FxcPub in time");
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static SessionSettings exchangeAcceptorSettings(int port) throws Exception {
        return acceptor("EXCHANGE", port, "BROKER1", "BROKER2");
    }

    private static SessionSettings pubDropCopyAcceptorSettings(int port) throws Exception {
        return acceptor("FXCPUB", port, "BROKER1", "BROKER2");
    }

    private static SessionSettings acceptor(String sender, int port, String... targets) throws Exception {
        StringBuilder cfg = new StringBuilder("""
                [DEFAULT]
                ConnectionType=acceptor
                BeginString=FIX.4.4
                SenderCompID=%s
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=10
                SocketAcceptPort=%d
                """.formatted(sender, port));
        for (String target : targets) {
            cfg.append("\n[SESSION]\nTargetCompID=").append(target).append('\n');
        }
        return new SessionSettings(new ByteArrayInputStream(cfg.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
