package com.fxc.investor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.BrokerServer;
import com.fxc.broker.oms.FixSettingsFactory;
import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.investor.ofx.OfxBrokerClient;
import com.fxc.investor.strategy.MarketView;
import com.fxc.investor.strategy.OrderIntent;
import com.fxc.investor.strategy.PortfolioView;
import com.fxc.investor.strategy.Strategies;
import com.fxc.investor.strategy.Strategy;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * Exercises the broker's order-book-snapshot relay (FxcBroker/docs/stories/001): a multi-level book
 * rested on FxcExchange is relayed — over FIX to the broker, then over OFX to the investor — and
 * fed into {@code booker}'s histogram, so `booker` prices from real depth.
 *
 * <p>OFX/FIX only (embedded exchange + broker); no Tigase/Docker needed.
 */
class BookRelayIntegrationTest {

    /** Rests resting book liquidity on the exchange as BROKER2. */
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

        void rest(char side, String symbol, String price, int qty, String id) throws Exception {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID(id), new quickfix.field.Side(side), new TransactTime(), new OrdType(OrdType.LIMIT));
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
    void bookRelaysFromExchangeToInvestorAndFeedsBooker(@TempDir java.nio.file.Path workDir) throws Exception {
        int exchangeFixPort = freePort();

        try (ExchangeServer exchange = ExchangeServer.start(
                exchangeAcceptorSettings(exchangeFixPort), "fxc-exchange-brit", 47551,
                workDir.resolve("ex").toString(), InstrumentCatalog.defaults());
             LiquidityClient liquidity = new LiquidityClient(
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER2", "EXCHANGE"));
             BrokerServer broker = BrokerServer.start(
                     "fxc-broker-brit", 47552, workDir.resolve("bk").toString(),
                     FixSettingsFactory.initiator("127.0.0.1", exchangeFixPort, "BROKER1", "EXCHANGE"),
                     "127.0.0.1", 0, "investor", "secret", "FXC-BROKER",
                     accounts -> accounts.seedAccount("000123456", "Dev Investor", "USD",
                             Map.of("USD", new BigDecimal("1000000"))),
                     null)) {

            liquidity.start();
            assertTrue(liquidity.awaitLogon(), "liquidity should log on");

            // A non-crossing two-level book on each side.
            liquidity.rest(quickfix.field.Side.BUY, "ACME", "42.08", 100, "B1");
            liquidity.rest(quickfix.field.Side.BUY, "ACME", "42.09", 200, "B2");
            liquidity.rest(quickfix.field.Side.SELL, "ACME", "42.11", 150, "S1");
            liquidity.rest(quickfix.field.Side.SELL, "ACME", "42.12", 250, "S2");

            OfxBrokerClient ofx = new OfxBrokerClient(
                    "http://127.0.0.1:" + broker.ofxPort() + "/ofx", "investor", "secret", "FXC-BROKER");

            // Poll until the broker has relayed all four book levels (FIX → cache is async).
            waitUntil(() -> {
                try {
                    return ofx.requestBook("ACME", 10).size() == 4;
                } catch (Exception e) {
                    return false;
                }
            }, 10_000);

            List<MarketView.Level> book = ofx.requestBook("ACME", 10);
            assertEquals(4, book.size(), "investor should receive all four book levels");
            assertTrue(hasLevel(book, "42.09", "200"), "bid 42.09 x200 should be relayed");
            assertTrue(hasLevel(book, "42.08", "100"), "bid 42.08 x100 should be relayed");
            assertTrue(hasLevel(book, "42.11", "150"), "ask 42.11 x150 should be relayed");
            assertTrue(hasLevel(book, "42.12", "250"), "ask 42.12 x250 should be relayed");

            // Feed booker: histogram over {42.08:100, 42.09:200, 42.11:150, 42.12:250}, last sale
            // 42.10. Weighted σ ≈ 0.0154, so the 1σ band admits {42.09, 42.11}.
            MarketView market = new MarketView();
            market.setLastSale("ACME", new BigDecimal("42.10"));
            market.setBook("ACME", book);
            Strategy booker = Strategies.byName("booker");
            Random rng = new Random(7);
            for (int i = 0; i < 40; i++) {
                Optional<OrderIntent> decision = booker.decide("ACME", market, PortfolioView.empty(), rng);
                BigDecimal price = decision.orElseThrow().price();
                assertTrue(price.compareTo(new BigDecimal("42.09")) == 0 || price.compareTo(new BigDecimal("42.11")) == 0,
                        "booker price " + price + " should come from the 1σ book bins {42.09, 42.11}");
            }
        }
    }

    private static boolean hasLevel(List<MarketView.Level> book, String price, String size) {
        return book.stream().anyMatch(l -> l.price().compareTo(new BigDecimal(price)) == 0
                && l.quantity().compareTo(new BigDecimal(size)) == 0);
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
