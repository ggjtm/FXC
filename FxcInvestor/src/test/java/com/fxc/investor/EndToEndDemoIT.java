package com.fxc.investor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.broker.BrokerServer;
import com.fxc.broker.model.OrderStatus;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.investor.agent.InvestorAgent;
import com.fxc.investor.agent.SubmittedOrder;
import com.fxc.investor.feed.FeedClient;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategies;
import com.fxc.pub.PubServer;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
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
 * Phase 6 exit criteria (PLAN §Phase 6): the full four-component stack, end to end. This is the
 * programmatic form of the {@code demo} script — it boots FxcExchange, FxcBroker (with its
 * drop-copy leg), and FxcPub against the running Tigase, seeds two investor accounts, and drives an
 * autonomous {@code rando} agent over OFX. It then asserts the whole chain closed:
 *
 * <ol>
 *   <li>the agent's OFX order routes to the exchange and <b>fills</b> against contra liquidity;</li>
 *   <li>the fill is drop-copied to FxcPub and published to the broker's feed; and</li>
 *   <li>the investor, subscribed to that feed as an ordinary <b>XMPP client</b>, sees the fill
 *       status and folds it back into its own {@link MarketView} last-sale.</li>
 * </ol>
 *
 * <p>Requires the Tigase container; skips (does not fail the build) when 127.0.0.1:5222 is closed.
 * Mirrors {@code docker compose up} + the {@code scripts/demo.sh} walkthrough (README "Demo").
 */
class EndToEndDemoIT {

    private static final String HOST = "127.0.0.1";
    private static final String DOMAIN = "fxc.local";
    private static final String BROKER_ID = "FXC-BROKER";
    private static final String ACCOUNT_A = "000123456";
    private static final String ACCOUNT_B = "000654321";

    /** Rests two-sided contra liquidity on the exchange as BROKER2 so any rando side/price fills. */
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

        void rest(char side, String symbol, String price, int qty) throws Exception {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID("LQ-" + side + "-" + symbol + "-" + qty),
                    new quickfix.field.Side(side), new TransactTime(), new OrdType(OrdType.LIMIT));
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
    void agentTradeFillsAndSurfacesOnInvestorFeed(@TempDir java.nio.file.Path workDir) throws Exception {
        assumeTrue(reachable(HOST, 5222), "Tigase not running on " + HOST + ":5222 — skipping");

        int exchangeFixPort = freePort();
        int pubFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-e2e", 47551,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             PubServer pub = PubServer.start(
                     "fxc-pub-e2e", 47553, workDir.resolve("pub").toString(),
                     HOST, 5222, DOMAIN, "pub-service", "secret",
                     pubDropCopyAcceptorSettings(pubFixPort));
             LiquidityClient liquidity = new LiquidityClient(
                     FixSettingsFactory.initiator(HOST, exchangeFixPort, "BROKER2", "EXCHANGE"));
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-e2e", 47552, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator(HOST, exchangeFixPort, "BROKER1", "EXCHANGE"),
                     HOST, 0, "investor", "secret", BROKER_ID,
                     accounts -> {
                         // Phase-6 exit criteria: two investor accounts seeded.
                         accounts.seedAccount(ACCOUNT_A, "Investor A", "USD",
                                 Map.of("USD", new BigDecimal("1000000")));
                         accounts.seedShares(ACCOUNT_A, "ACME", new BigDecimal("1000"), new BigDecimal("42.00"));
                         accounts.seedAccount(ACCOUNT_B, "Investor B", "USD",
                                 Map.of("USD", new BigDecimal("1000000")));
                         accounts.seedShares(ACCOUNT_B, "ACME", new BigDecimal("1000"), new BigDecimal("42.00"));
                     },
                     FixSettingsFactory.initiator(HOST, pubFixPort, "BROKER1", "FXCPUB"))) {

            liquidity.start();
            assertTrue(liquidity.awaitLogon(), "liquidity should log on to the exchange");

            // The investor connects to FxcPub as an ordinary XMPP client and subscribes to the
            // broker's feed BEFORE trading, so the fill status is delivered live.
            OfxBrokerClient ofx = new OfxBrokerClient(
                    "http://" + HOST + ":" + broker.ofxPort() + "/ofx", "investor", "secret", BROKER_ID);
            MarketView market = new MarketView();
            market.setLastSale("ACME", new BigDecimal("42.10"));

            try (FeedClient feed = new FeedClient(HOST, 5222, DOMAIN)) {
                feed.connect("investor", "secret");
                awaitFeed(broker, feed, 15_000); // broker creates feed-BROKER1 on drop-copy logon
                feed.subscribeFeed("BROKER1", market);

                InvestorAgent agent = new InvestorAgent(ACCOUNT_A, ofx, Strategies.byName("rando"),
                        market, new Random(42), "INV");

                // rando decides autonomously and submits over OFX; with an empty book it rests.
                Optional<SubmittedOrder> submitted = agent.step("ACME", PortfolioView.empty());
                assertTrue(submitted.isPresent(), "rando should submit an order given a last sale");
                SubmittedOrder order = submitted.get();
                assertTrue(!"REJECTED".equals(order.status()) && !"NO_RESPONSE".equals(order.status()),
                        "order should be accepted by the broker, was: " + order.status());

                // Contra liquidity crosses whatever side/price rando chose.
                char contra = order.intent().side() == com.fxc.investor.strategy.Side.BUY
                        ? quickfix.field.Side.SELL : quickfix.field.Side.BUY;
                liquidity.rest(contra, "ACME", order.snappedPrice().toPlainString(), 100);

                // (1) the order fills at the broker.
                waitUntil(() -> broker.omsService().order(order.clOrdId())
                        .map(o -> o.status() == OrderStatus.FILLED).orElse(false), 15_000);
                var filled = broker.omsService().order(order.clOrdId()).orElseThrow();
                assertTrue(filled.status() == OrderStatus.FILLED,
                        "the agent's order should fill, was: " + filled.status());

                // (2)+(3) the fill surfaces on the investor's XMPP feed subscription.
                waitUntil(() -> feed.recentStatuses(10).stream()
                        .anyMatch(s -> s.contains("FILLED") && s.contains("ACME")), 15_000);
                assertTrue(feed.recentStatuses(10).stream()
                                .anyMatch(s -> s.contains("FILLED") && s.contains("ACME")),
                        "the fill should appear on the investor's feed, saw: " + feed.recentStatuses(10));

                // The feed handler folds the fill back into the investor's market view.
                assertTrue(market.lastSale("ACME").isPresent(),
                        "the investor's market view should record a last-sale from the fill status");
            }
        }
    }

    private static void awaitFeed(BrokerServer broker, FeedClient feed, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                feed.ensureFeed("BROKER1");
                return;
            } catch (Exception notYet) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("feed-BROKER1 was not available in time");
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
