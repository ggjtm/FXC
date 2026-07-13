package com.fxc.investor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.BrokerServer;
import com.fxc.broker.model.OrderStatus;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.investor.agent.InvestorAgent;
import com.fxc.investor.agent.SubmittedOrder;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategies;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
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
 * Phase 4 exit path (PLAN §Phase 4): the {@code rando} agent decides autonomously and trades
 * end-to-end over OFX against a live FxcBroker + FxcExchange, and the order fills. (Feed-visible
 * end-to-end incl. FxcPub is the Phase-6 demo; this proves the agent → OFX → fill path.)
 *
 * <p>Two-sided contra liquidity (BROKER2) around the last sale guarantees a fill regardless of the
 * random side/price; the account is seeded with cash and shares so both buys and sells are fundable.
 */
class InvestorIntegrationTest {

    /** Rests two-sided liquidity on the exchange as BROKER2. */
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
    void randoAgentTradesEndToEndOverOfxAndFills(@TempDir java.nio.file.Path workDir) throws Exception {
        int exchangeFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-iit", 47541,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             LiquidityClient liquidity = new LiquidityClient(
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER2", "EXCHANGE"));
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-iit", 47542, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER1", "EXCHANGE"),
                     "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                     accounts -> {
                         accounts.seedAccount("000123456", "Dev Investor", "USD",
                                 Map.of("USD", new BigDecimal("1000000")));
                         accounts.seedShares("000123456", "ACME", new BigDecimal("1000"), new BigDecimal("42.00"));
                     },
                     null)) {

            liquidity.start();
            assertTrue(liquidity.awaitLogon(), "liquidity should log on");

            OfxBrokerClient ofx = new OfxBrokerClient(
                    "http://127.0.0.1:" + broker.ofxPort() + "/ofx", "investor", "secret", "FXC-BROKER");
            MarketView market = new MarketView();
            market.setLastSale("ACME", new BigDecimal("42.10"));
            InvestorAgent agent = new InvestorAgent("000123456", ofx, Strategies.byName("rando"),
                    market, new Random(42), "INV");

            // rando submits an order; with an empty book it rests on the exchange.
            Optional<SubmittedOrder> submitted = agent.step("ACME", PortfolioView.empty());
            assertTrue(submitted.isPresent(), "rando should submit an order given a last sale");
            SubmittedOrder order = submitted.get();
            assertTrue(!"REJECTED".equals(order.status()) && !"NO_RESPONSE".equals(order.status()),
                    "order should be accepted by the broker, was: " + order.status());

            // Rest contra liquidity that crosses whatever side/price rando chose (100 units covers
            // rando's 1–10). Priced at the rando order's price so it crosses at that level.
            char contra = order.intent().side() == com.fxc.investor.strategy.Side.BUY
                    ? quickfix.field.Side.SELL : quickfix.field.Side.BUY;
            liquidity.rest(contra, "ACME", order.snappedPrice().toPlainString(), 100);

            // Fill is async over FIX; poll the broker's OMS for this order.
            waitUntil(() -> broker.omsService().order(order.clOrdId())
                    .map(o -> o.status() == OrderStatus.FILLED).orElse(false), 10_000);

            var filled = broker.omsService().order(order.clOrdId()).orElseThrow();
            assertTrue(filled.status() == OrderStatus.FILLED,
                    "rando's order should fill against contra liquidity, was: " + filled.status());
            assertTrue(filled.cumQty().compareTo(order.intent().quantity()) == 0,
                    "cumQty should equal the ordered quantity");
        }
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
