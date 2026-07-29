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
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.util.List;
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

    private static final Instrument ACME = InstrumentCatalog.bySymbol().get("ACME");
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
        accounts.applyFill(ACCOUNT, ACME, side, new BigDecimal(qty), new BigDecimal(px));
        pnl.onFill(ACCOUNT, ACME, side, new BigDecimal(qty), new BigDecimal(px), 2_000L);
    }

    @Test
    void baselineStartsFlatAndIsAnchoredAtZeroTrades(@TempDir java.nio.file.Path workDir) throws Exception {
        withServices(workDir, 47581, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("100"), new BigDecimal("42.00"));
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
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ACME", new BigDecimal("42.00"));

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
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ACME", new BigDecimal("42.00"));

            fill(accounts, pnl, Side.SELL, "10", "45.00");

            AccountPnl p = only(pnl, ACCOUNT);
            // 10 shares sold 3.00 above a 42.00 basis.
            eq("30", p.realized());
            // Cash 10,450 + 90 shares still marked at 42.00 = 14,230.
            eq("14230", p.equity());
            eq("30", p.relative());
            eq("0", p.unrealized());
            eq("42.00000000", accounts.positions(ACCOUNT).stream()
                    .filter(pos -> pos.instrument().equals("ACME")).findFirst().orElseThrow().avgPrice());
        });
    }

    @Test
    void markMovingUpShowsAsUnrealizedAndTheTwoPartsSumToRelative(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withServices(workDir, 47584, (accounts, md, pnl) -> {
            accounts.seedAccount(ACCOUNT, "Dev", "USD", Map.of("USD", new BigDecimal("10000")));
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ACME", new BigDecimal("42.00"));
            fill(accounts, pnl, Side.SELL, "10", "45.00");

            // The remaining 90 shares are revalued from 42.00 to 45.00: +270 unrealized.
            md.setLastPrice("ACME", new BigDecimal("45.00"));

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
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("100"), new BigDecimal("42.00"));
            pnl.captureBaselines();
            md.setLastPrice("ACME", new BigDecimal("42.00"));

            fill(accounts, pnl, Side.BUY, "5", "42.00");
            md.setLastPrice("ACME", new BigDecimal("43.00"));
            fill(accounts, pnl, Side.SELL, "5", "43.00");
            md.setLastPrice("ACME", new BigDecimal("41.00"));
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
            Instrument eurusd = InstrumentCatalog.bySymbol().get("EUR/USD");
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
            accounts.seedShares(ACCOUNT, "ACME", new BigDecimal("50"), BigDecimal.ZERO);
            pnl.captureBaselines();

            AccountPnl p = only(pnl, ACCOUNT);
            eq("1000", p.baseline());
            assertEquals(1, p.unpricedHoldings());
            assertTrue(p.points().size() == 1);
        });
    }

    private static void eq(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " (expected " + expected + " got " + actual + ")");
    }
}
