package com.fxc.broker.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.md.MarketDataCache;
import com.fxc.broker.model.Side;
import com.fxc.broker.pnl.PnlService.AccountPnl;
import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.util.List;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Session P&amp;L arithmetic for the broker console (docs/DESIGN.md §6), against scripted fills and
 * fixed marks so every figure is exact.
 *
 * <p>Fills are applied the way {@code OmsService} applies them — position first, then the listener —
 * because the realized-P&amp;L calculation depends on reading the cost basis after the fill (an equity
 * sell leaves {@code avg_price} untouched, which is what makes that safe).
 */
class PnlServiceTest {

    private static final Instrument ARVX = InstrumentCatalog.bySymbol().get("ARVX");
    private static final String ACCOUNT = "000123456";

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    /** Runs a scenario against a real grid node, as the repo's service tests do. */
    private interface Scenario {
        void run(AccountService accounts, MarketDataCache md, PnlService pnl);
    }

    private static void withServices(java.nio.file.Path workDir, int discoveryPort, Scenario scenario)
            throws Exception {
        try (GridNode node = GridNode.start("fxc-broker-pnl-" + discoveryPort, discoveryPort,
                workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            MarketDataCache md = new MarketDataCache();
            PnlService pnl = new PnlService(accounts, md, () -> 1_000L);
            scenario.run(accounts, md, pnl);
        }
    }

    private static AccountPnl only(PnlService pnl, String account) {
        return pnl.series().stream().filter(p -> p.account().equals(account)).findFirst().orElseThrow();
    }

    /** Apply a fill exactly as OmsService does: position first, then notify. */
    private static void fill(AccountService accounts, PnlService pnl, Side side, String qty, String px) {
        accounts.applyFill(ACCOUNT, ARVX, side, new BigDecimal(qty), new BigDecimal(px));
        pnl.onFill(ACCOUNT, ARVX, side, new BigDecimal(qty), new BigDecimal(px), 2_000L);
    }

    @Test
    void baselineStartsFlatAndIsAnchoredAtZeroTrades(@TempDir java.nio.file.Path workDir) throws Exception {
        withServices(workDir, 47581, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();

            AccountPnl p = only(pnl, ACCOUNT);
            // Cash 10,000 + 100 shares marked at their 42.00 cost basis (no last sale yet).
            eq("14200", p.baseline());
            eq("14200", p.equity());
            eq("0", p.relative());
            eq("0", p.realized());
            eq("0", p.unrealized());
            assertEquals(0, p.tradeCount());
            assertEquals(0, p.unpricedHoldings());
            assertEquals(1, p.points().size(), "the curve is anchored at zero trades");
            assertEquals(0, p.points().get(0).tradeCount());
            eq("0", p.points().get(0).relative());
        });
    }

    @Test
    void buyingAtTheMarkMovesNothing(@TempDir java.nio.file.Path workDir) throws Exception {
        withServices(workDir, 47582, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ARVX", new BigDecimal("42.00"));

            fill(accounts, pnl, Side.BUY, "10", "42.00");

            AccountPnl p = only(pnl, ACCOUNT);
            // Cash 9,580 + 110 shares at 42.00 = 14,200 — cash became stock, nothing was earned.
            eq("14200", p.equity());
            eq("0", p.relative());
            eq("0", p.realized());
            assertEquals(1, p.tradeCount());
        });
    }

    @Test
    void sellingAboveCostRealizesTheGain(@TempDir java.nio.file.Path workDir) throws Exception {
        withServices(workDir, 47583, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ARVX", new BigDecimal("42.00"));

            fill(accounts, pnl, Side.SELL, "10", "45.00");

            AccountPnl p = only(pnl, ACCOUNT);
            // 10 shares sold 3.00 above a 42.00 basis.
            eq("30", p.realized());
            // Cash 10,450 + 90 shares still marked at 42.00 = 14,230.
            eq("14230", p.equity());
            eq("30", p.relative());
            eq("0", p.unrealized());
            eq("42.00000000", accounts.positions(ACCOUNT).stream()
                    .filter(pos -> pos.instrument().equals("ARVX")).findFirst().orElseThrow().avgPrice());
        });
    }

    @Test
    void markMovingUpShowsAsUnrealizedAndTheTwoPartsSumToRelative(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47584, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ARVX", new BigDecimal("42.00"));
            fill(accounts, pnl, Side.SELL, "10", "45.00");

            // The remaining 90 shares are revalued from 42.00 to 45.00: +270 unrealized.
            md.setLastPrice("ARVX", new BigDecimal("45.00"));

            AccountPnl p = only(pnl, ACCOUNT);
            eq("14500", p.equity());   // 10,450 cash + 90 * 45.00
            eq("300", p.relative());
            eq("30", p.realized());
            eq("270", p.unrealized());
            eq(p.relative().toPlainString(), p.realized().add(p.unrealized()));
        });
    }

    @Test
    void everyPointKeepsRealizedPlusUnrealizedEqualToRelative(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47585, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ARVX", new BigDecimal("42.00"));

            fill(accounts, pnl, Side.BUY, "5", "42.00");
            md.setLastPrice("ARVX", new BigDecimal("43.00"));
            fill(accounts, pnl, Side.SELL, "5", "43.00");
            md.setLastPrice("ARVX", new BigDecimal("41.00"));
            fill(accounts, pnl, Side.BUY, "20", "41.00");

            AccountPnl p = only(pnl, ACCOUNT);
            assertEquals(4, p.points().size(), "anchor + one point per fill");
            for (PnlPoint point : p.points()) {
                eq(point.relative().toPlainString(), point.realized().add(point.unrealized()));
            }
            List<Integer> counts = p.points().stream().map(PnlPoint::tradeCount).toList();
            assertEquals(List.of(0, 1, 2, 3), counts, "trade count is the x axis and must increase by one");
            assertEquals(3, p.tradeCount());
        });
    }

    @Test
    void fxBalancesAreConvertedAndUnconvertibleOnesAreCountedNotGuessed(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47586, (accounts, md, pnl) -> {
            // EUR is priceable at baseline; CHF never is (no pair for it in the catalog).
            md.setLastPrice("EUR/USD", new BigDecimal("1.10"));
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of(
                    "USD", new BigDecimal("1000"),
                    "EUR", new BigDecimal("2000"),
                    "CHF", new BigDecimal("500")));
            pnl.captureBaselines();

            AccountPnl p = only(pnl, ACCOUNT);
            eq("3200", p.baseline());   // 1,000 USD + 2,000 EUR at 1.10; CHF excluded
            assertEquals(1, p.unpricedHoldings(), "the CHF balance must be reported, not valued at zero");
        });
    }

    @Test
    void anFxFillShowsUpThroughRevaluationRatherThanRealized(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47587, (accounts, md, pnl) -> {
            // Built here rather than resolved: EUR/USD is no longer LISTED (fxc/docs/PROBLEMS.md
            // P23), but FX revaluation and FxRates are still live code worth covering. PnlService
            // takes an Instrument, it never looks one up.
            Instrument eurusd = FxSpotInstrument.of(Currency.getInstance("EUR"),
                    Currency.getInstance("USD"), new BigDecimal("0.00001"), new BigDecimal("1000"));
            md.setLastPrice("EUR/USD", new BigDecimal("1.10"));
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            pnl.captureBaselines();
            eq("10000", only(pnl, ACCOUNT).baseline());

            // Buy 1,000 EUR at 1.10: pay 1,100 USD, receive 1,000 EUR — a wash at the current rate.
            accounts.applyFill(ACCOUNT, eurusd, Side.BUY, new BigDecimal("1000"), new BigDecimal("1.10"));
            pnl.onFill(ACCOUNT, eurusd, Side.BUY, new BigDecimal("1000"), new BigDecimal("1.10"), 2_000L);

            AccountPnl afterFill = only(pnl, ACCOUNT);
            eq("10000", afterFill.equity());
            eq("0", afterFill.relative());
            eq("0", afterFill.realized(), "an FX spot fill is a balance swap, not a closed trade");

            // EUR strengthens: the position revalues, and that is where the P&L appears.
            md.setLastPrice("EUR/USD", new BigDecimal("1.20"));
            AccountPnl afterMove = only(pnl, ACCOUNT);
            eq("10100", afterMove.equity());   // 8,900 USD + 1,000 EUR at 1.20
            eq("100", afterMove.relative());
            eq("0", afterMove.realized());
            eq("100", afterMove.unrealized());
        });
    }

    @Test
    void aShareWithNeitherMarkNorBasisIsReportedUnpriced(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47588, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("1000")));
            accounts.seedShares(ACCOUNT, "ARVX", new BigDecimal("50"), BigDecimal.ZERO);
            pnl.captureBaselines();

            AccountPnl p = only(pnl, ACCOUNT);
            eq("1000", p.baseline());
            assertEquals(1, p.unpricedHoldings());
            assertTrue(p.points().size() == 1);
        });
    }

    // --- the rolling window (docs/stories/003) ---

    /** A clock the test moves by hand, so eviction can be driven without sleeping. */
    private static final class Clock implements java.util.function.LongSupplier {
        private long now = 1_000L;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    private static void withWindow(java.nio.file.Path workDir, int discoveryPort, PnlSettings settings,
                                   java.util.function.BiConsumer<Clock, PnlService> scenario)
            throws Exception {
        Clock clock = new Clock();
        try (GridNode node = GridNode.start("fxc-broker-pnl-" + discoveryPort, discoveryPort,
                workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            PnlService pnl = new PnlService(accounts, new MarketDataCache(), clock, settings);
            pnl.captureBaselines();
            scenario.accept(clock, pnl);
        }
    }

    /** A cash-only fill, timestamped by the test clock — no instrument, so equity does not move. */
    private static void tick(Clock clock, PnlService pnl, long advanceMs) {
        clock.advance(advanceMs);
        pnl.onFill(ACCOUNT, null, Side.BUY, BigDecimal.ONE, new BigDecimal("42.00"), clock.getAsLong());
    }

    @Test
    void pointsOlderThanTheWindowAreDropped(@TempDir java.nio.file.Path workDir) throws Exception {
        PnlSettings settings = new PnlSettings(10_000L, 600, 8, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
        withWindow(workDir, 47590, settings, (clock, pnl) -> {
            for (int i = 0; i < 10; i++) {
                tick(clock, pnl, 1_000L);      // one fill per second for ten seconds
            }
            assertTrue(only(pnl, ACCOUNT).points().size() > 5, "the window should still be filling");

            tick(clock, pnl, 60_000L);         // a minute later: everything before is out of window

            AccountPnl p = only(pnl, ACCOUNT);
            assertEquals(1, p.points().size(), "only the newest point is inside a 10s window");
            assertTrue(p.dropped() >= 10, "what aged out is counted, not hidden: " + p.dropped());
            assertEquals(10_000L, p.windowMs());
        });
    }

    @Test
    void anIdleAccountAgesOutOnReadNotJustOnFill(@TempDir java.nio.file.Path workDir) throws Exception {
        // Nothing arrives to trigger eviction, so a read has to do it — otherwise a stopped agent's
        // curve would hang on screen for as long as the console is open.
        PnlSettings settings = new PnlSettings(10_000L, 600, 8, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
        withWindow(workDir, 47591, settings, (clock, pnl) -> {
            for (int i = 0; i < 5; i++) {
                tick(clock, pnl, 1_000L);
            }
            assertTrue(only(pnl, ACCOUNT).points().size() > 1);

            clock.advance(120_000L);
            assertEquals(0, only(pnl, ACCOUNT).points().size(), "everything is older than the window");
        });
    }

    @Test
    void totalsSurviveEviction(@TempDir java.nio.file.Path workDir) throws Exception {
        // The table must not change because the chart forgot something: totals are computed from live
        // positions, never summed from the curve.
        PnlSettings settings = new PnlSettings(5_000L, 600, 8, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
        withWindow(workDir, 47592, settings, (clock, pnl) -> {
            for (int i = 0; i < 20; i++) {
                tick(clock, pnl, 1_000L);
            }
            AccountPnl p = only(pnl, ACCOUNT);
            assertEquals(20, p.tradeCount(), "every fill still counted");
            eq("10000", p.equity());
            eq("0", p.relative());
        });
    }

    @Test
    void aBurstInsideTheWindowIsCappedButKeepsRolling(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // The backstop: more fills inside the window than maxRetainedPoints. The oldest go, the curve
        // keeps moving — the previous design froze instead.
        PnlSettings settings = new PnlSettings(900_000L, 600, 8, 50, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
        withWindow(workDir, 47593, settings, (clock, pnl) -> {
            for (int i = 0; i < 200; i++) {
                tick(clock, pnl, 10L);
            }
            AccountPnl p = only(pnl, ACCOUNT);
            assertTrue(p.points().size() <= 50, "retained " + p.points().size());
            assertTrue(p.dropped() >= 150, "dropped " + p.dropped());
            assertEquals(200, p.tradeCount());
        });
    }

    @Test
    void theChartCarriesTheBestTheBusiestAndTheWorst(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // One "busiest N" answered only one of the three questions this chart is read for. With an
        // account per agent, "who is winning", "who is trading" and "who is losing" are different
        // accounts, and an operator wants all three (docs/stories/005).
        try (GridNode node = GridNode.start("fxc-broker-pnl-47594", 47594, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            MarketDataCache md = new MarketDataCache();
            for (int i = 1; i <= 6; i++) {
                accounts.seedAccount("00010000" + i, "Dev", "USD", Map.of("USD", new BigDecimal("1000")));
                accounts.seedShares("00010000" + i, "ARVX", new BigDecimal("100"), new BigDecimal("10.00"));
            }
            // groupSize = 1: exactly one winner, one most-active and one loser carry a curve.
            PnlService pnl = new PnlService(accounts, md, () -> 1_000L,
                    new PnlSettings(900_000L, 600, 1, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW));
            pnl.captureBaselines();

            // Account 1 gains (it sold above its cost basis), account 2 loses, account 3 trades most.
            accounts.applyFill("000100001", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("20.00"));
            pnl.onFill("000100001", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("20.00"), 2_000L);
            accounts.applyFill("000100002", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("1.00"));
            pnl.onFill("000100002", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("1.00"), 2_000L);
            for (int i = 0; i < 20; i++) {
                pnl.onFill("000100003", null, Side.BUY, BigDecimal.ONE, new BigDecimal("1"), 2_000L);
            }

            Map<String, AccountPnl> byAccount = new java.util.HashMap<>();
            pnl.series().forEach(p -> byAccount.put(p.account(), p));

            assertEquals(List.of("top"), byAccount.get("000100001").groups(), "the winner");
            assertEquals(List.of("bottom"), byAccount.get("000100002").groups(), "the loser");
            assertEquals(List.of("active"), byAccount.get("000100003").groups(), "the busiest");
            for (String account : List.of("000100001", "000100002", "000100003")) {
                assertTrue(byAccount.get(account).plotted(), account + " should carry a curve");
                assertTrue(!byAccount.get(account).points().isEmpty(), account + " should have points");
            }
            // Everyone else is off the chart but fully reported in the table.
            for (String account : List.of("000100004", "000100005", "000100006")) {
                assertTrue(byAccount.get(account).groups().isEmpty());
                assertTrue(!byAccount.get(account).plotted());
                assertTrue(byAccount.get(account).points().isEmpty());
                eq("2000", byAccount.get(account).equity());
            }
            assertEquals(6, byAccount.size(), "every account is still reported");
        }
    }

    @Test
    void anAccountCanBeBothTheBusiestAndTheWorst(@TempDir java.nio.file.Path workDir) throws Exception {
        // The overlap is the interesting case, and the one an operator most wants to see: the account
        // doing all the trading is often the one losing all the money.
        try (GridNode node = GridNode.start("fxc-broker-pnl-47596", 47596, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            for (int i = 1; i <= 3; i++) {
                accounts.seedAccount("00010000" + i, "Dev", "USD", Map.of("USD", new BigDecimal("1000")));
                accounts.seedShares("00010000" + i, "ARVX", new BigDecimal("100"), new BigDecimal("10.00"));
            }
            PnlService pnl = new PnlService(accounts, new MarketDataCache(), () -> 1_000L,
                    new PnlSettings(900_000L, 600, 1, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW));
            pnl.captureBaselines();

            // Account 1 trades once and profits, so the "best" slot is genuinely contested — with a
            // single trader an account would be trivially best and worst at the same time.
            accounts.applyFill("000100001", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("20.00"));
            pnl.onFill("000100001", ARVX, Side.SELL, new BigDecimal("10"), new BigDecimal("20.00"), 2_000L);
            // Account 2 both trades the most and loses the most.
            accounts.applyFill("000100002", ARVX, Side.SELL, new BigDecimal("50"), new BigDecimal("1.00"));
            for (int i = 0; i < 10; i++) {
                pnl.onFill("000100002", ARVX, Side.SELL, BigDecimal.ONE, new BigDecimal("1.00"), 2_000L);
            }

            AccountPnl worst = pnl.series().stream()
                    .filter(p -> p.account().equals("000100002")).findFirst().orElseThrow();
            assertEquals(List.of("active", "bottom"), worst.groups(),
                    "listed under both groups, drawn once");
        }
    }

    @Test
    void aResponseNeverCarriesMoreThanItsBudget(@TempDir java.nio.file.Path workDir) throws Exception {
        PnlSettings settings = new PnlSettings(900_000L, 20, 8, 5_000, PnlService.DEFAULT_INTERNAL_ACCOUNTS_BELOW);
        withWindow(workDir, 47595, settings, (clock, pnl) -> {
            for (int i = 0; i < 500; i++) {
                tick(clock, pnl, 10L);
            }
            assertEquals(20, only(pnl, ACCOUNT).points().size());
        });
    }

    // --- downsampling, driven directly: it is the part a reader can catch lying ---

    @Test
    void downsamplingKeepsTheEndpointsAndTheBudget() {
        List<PnlPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            points.add(new PnlPoint(i, i, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(i)));
        }
        List<PnlPoint> thinned = PnlService.downsample(points, 100);

        assertEquals(100, thinned.size());
        // The two values a reader actually checks against the axis are exact.
        assertEquals(points.get(0), thinned.get(0));
        assertEquals(points.get(999), thinned.get(99));
        // And it stays in order, so the line does not double back on itself.
        for (int i = 1; i < thinned.size(); i++) {
            assertTrue(thinned.get(i).tradeCount() > thinned.get(i - 1).tradeCount());
        }
    }

    @Test
    void downsamplingLeavesAShortSeriesAlone() {
        List<PnlPoint> points = List.of(
                new PnlPoint(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                new PnlPoint(1, 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE));
        assertEquals(points, PnlService.downsample(points, 600));
    }

    // --- internal accounts (issuer + market makers) stay off the console ---

    @Test
    void theBrokersOwnAccountsAreNotOnTheConsole(@TempDir java.nio.file.Path workDir) throws Exception {
        // The issuer (0) and the market makers (1, 2) hold the entire float, so mark-to-market on it
        // dwarfs every customer's P&L — left in, they take every group by orders of magnitude and the
        // chart stops being about the investors it exists to compare.
        try (GridNode node = GridNode.start("fxc-broker-pnl-47597", 47597, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            for (String internal : List.of("000000000", "000000001", "000000002")) {
                accounts.seedAccount(internal, "Internal", "USD", Map.of("USD", new BigDecimal("1000")));
                accounts.seedShares(internal, "ARVX", new BigDecimal("500000"), new BigDecimal("42.00"));
            }
            accounts.seedAccount("000100001", "Investor", "USD", Map.of("USD", new BigDecimal("1000")));

            PnlService pnl = new PnlService(accounts, new MarketDataCache(), () -> 1_000L,
                    new PnlSettings(900_000L, 600, 5, 5_000, 100));
            pnl.captureBaselines();
            // The market makers trade heavily; they must still not appear.
            for (int i = 0; i < 50; i++) {
                pnl.onFill("000000001", null, Side.BUY, BigDecimal.ONE, new BigDecimal("42.00"), 2_000L);
            }
            pnl.onFill("000100001", null, Side.BUY, BigDecimal.ONE, new BigDecimal("42.00"), 2_000L);

            List<String> reported = pnl.series().stream().map(AccountPnl::account).toList();
            assertEquals(List.of("000100001"), reported, "only the customer account is reported");
        }
    }

    @Test
    void theThresholdIsConfigurableAndZeroShowsEverything(@TempDir java.nio.file.Path workDir)
            throws Exception {
        try (GridNode node = GridNode.start("fxc-broker-pnl-47598", 47598, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            for (String account : List.of("000000001", "000000099", "000000100")) {
                accounts.seedAccount(account, "Dev", "USD", Map.of("USD", new BigDecimal("1000")));
            }

            PnlService hiding = new PnlService(accounts, new MarketDataCache(), () -> 1_000L,
                    new PnlSettings(900_000L, 600, 5, 5_000, 100));
            hiding.captureBaselines();
            assertEquals(List.of("000000100"), hiding.series().stream().map(AccountPnl::account).toList(),
                    "the boundary is exclusive: 99 is internal, 100 is a customer");

            PnlService showing = new PnlService(accounts, new MarketDataCache(), () -> 1_000L,
                    new PnlSettings(900_000L, 600, 5, 5_000, 0));
            showing.captureBaselines();
            assertEquals(3, showing.series().size(), "0 disables the filter");
        }
    }

    @Test
    void anAccountNumberThatIsNotANumberIsTreatedAsACustomers(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // Hiding something because it could not be parsed would be the wrong way round.
        try (GridNode node = GridNode.start("fxc-broker-pnl-47599", 47599, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            accounts.seedAccount("HOUSE-A", "Odd", "USD", Map.of("USD", new BigDecimal("1000")));
            PnlService pnl = new PnlService(accounts, new MarketDataCache(), () -> 1_000L,
                    new PnlSettings(900_000L, 600, 5, 5_000, 100));
            pnl.captureBaselines();
            assertEquals(List.of("HOUSE-A"), pnl.series().stream().map(AccountPnl::account).toList());
        }
    }

    private static void eq(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " (expected " + expected + " got " + actual + ")");
    }
}
