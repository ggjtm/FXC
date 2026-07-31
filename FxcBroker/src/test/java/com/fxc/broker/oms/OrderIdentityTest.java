package com.fxc.broker.oms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.model.ClientOrder;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Position;
import com.fxc.broker.model.Side;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Conservation of cash and shares through the fill path (docs/PROBLEMS.md P18).
 *
 * <p>These are the tests that were missing when a live demo quietly created 509 shares and destroyed
 * $18,451 of cash. Every one of them asserts the same invariant from a different angle: **a fill moves
 * balances between accounts and never mints or burns them**, so any report the broker cannot
 * confidently attribute must move nothing at all.
 */
class OrderIdentityTest {

    private static final String ACCOUNT_A = "000000001";
    private static final String ACCOUNT_B = "000000002";

    private interface Scenario {
        void run(OmsService oms, AccountService accounts);
    }

    /** A router that accepts everything and reports nothing back — the exchange is not under test. */
    private static final class SilentRouter implements OrderRouter {
        @Override
        public void route(ClientOrder order) {
        }
    }

    private static void withOms(java.nio.file.Path workDir, int discoveryPort, Scenario scenario)
            throws Exception {
        try (GridNode node = GridNode.start("fxc-broker-oid-" + discoveryPort, discoveryPort,
                workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            BrokerRepository repository = new BrokerRepository(node.ignite());
            AccountService accounts = new AccountService(repository);
            for (String account : new String[] {ACCOUNT_A, ACCOUNT_B}) {
                accounts.seedAccount(account, "Dev", "USD", Map.of("USD", new BigDecimal("100000")));
                accounts.seedShares(account, "ACME", new BigDecimal("1000"), new BigDecimal("42.00"));
            }
            OmsService oms = new OmsService(accounts, repository);
            oms.setRouter(new SilentRouter());
            scenario.run(oms, accounts);
        }
    }

    private static BigDecimal held(AccountService accounts, String account, String instrument,
                                   HoldingType type) {
        for (Position position : accounts.positions(account)) {
            if (position.holdingType() == type && position.instrument().equals(instrument)) {
                return position.quantity();
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal totalShares(AccountService accounts) {
        return held(accounts, ACCOUNT_A, "ACME", HoldingType.SHARE)
                .add(held(accounts, ACCOUNT_B, "ACME", HoldingType.SHARE));
    }

    private static BigDecimal totalCash(AccountService accounts) {
        return held(accounts, ACCOUNT_A, "USD", HoldingType.CASH)
                .add(held(accounts, ACCOUNT_B, "USD", HoldingType.CASH));
    }

    private static void submit(OmsService oms, String account, String clOrdId, Side side, String px,
                               String qty) {
        oms.submit(account, clOrdId, "ACME", side, OrderType.LIMIT, new BigDecimal(px),
                new BigDecimal(qty));
    }

    @Test
    void aReusedClientOrderIdIsRejectedRatherThanOverwriting(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // Two agents that share an id prefix and restart their counters emit the same ids. The old
        // behaviour replaced the first order in the map, and later fills for it landed on the second
        // agent's account and side.
        withOms(workDir, 47620, (oms, accounts) -> {
            OrderResult first = oms.submit(ACCOUNT_A, "INV-1", "ACME", Side.BUY, OrderType.LIMIT,
                    new BigDecimal("42.00"), new BigDecimal("5"));
            OrderResult second = oms.submit(ACCOUNT_B, "INV-1", "ACME", Side.SELL, OrderType.LIMIT,
                    new BigDecimal("42.00"), new BigDecimal("5"));

            assertTrue(first.accepted());
            assertFalse(second.accepted(), "the second order must not silently replace the first");
            assertTrue(second.order().rejectReason().contains("duplicate"), second.order().rejectReason());
            // The id still resolves to the order that claimed it.
            assertEquals(ACCOUNT_A, oms.order("INV-1").orElseThrow().account());
            assertEquals(Side.BUY, oms.order("INV-1").orElseThrow().side());
        });
    }

    @Test
    void aDuplicateExecutionReportMovesNothingTheSecondTime(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // A FIX resend after a reconnect delivers the same report twice. The execution *table*
        // deduplicates (it MERGEs on exec_id), so a double-apply corrupted balances invisibly.
        withOms(workDir, 47621, (oms, accounts) -> {
            submit(oms, ACCOUNT_A, "A-1", Side.BUY, "42.00", "10");
            BigDecimal sharesBefore = totalShares(accounts);
            BigDecimal cashBefore = totalCash(accounts);

            oms.onExecutionReport("A-1", "EXEC-1", "EX-1", true, false, new BigDecimal("10"),
                    new BigDecimal("42.00"), new BigDecimal("10"), null, Side.BUY);
            BigDecimal sharesAfterFirst = held(accounts, ACCOUNT_A, "ACME", HoldingType.SHARE);

            oms.onExecutionReport("A-1", "EXEC-1", "EX-1", true, false, new BigDecimal("10"),
                    new BigDecimal("42.00"), new BigDecimal("10"), null, Side.BUY);

            assertEquals(0, sharesAfterFirst.compareTo(held(accounts, ACCOUNT_A, "ACME", HoldingType.SHARE)),
                    "the replay must not add shares a second time");
            assertEquals(1, oms.duplicateReportCount());
            // One fill of 10 shares against one account: +10 shares, -420 cash. No more, no less.
            assertEquals(0, totalShares(accounts).subtract(sharesBefore).compareTo(new BigDecimal("10")));
            assertEquals(0, totalCash(accounts).subtract(cashBefore).compareTo(new BigDecimal("-420")));
        });
    }

    @Test
    void aReportWhoseSideDisagreesWithTheOrderIsIgnored(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // The signature of the live corruption: the broker applied `order.side()` from its own map and
        // never looked at the side the exchange reported, so a report belonging to a different order
        // moved the wrong balance in the wrong direction.
        withOms(workDir, 47622, (oms, accounts) -> {
            submit(oms, ACCOUNT_A, "A-1", Side.BUY, "42.00", "10");
            BigDecimal shares = totalShares(accounts);
            BigDecimal cash = totalCash(accounts);

            oms.onExecutionReport("A-1", "EXEC-9", "EX-9", true, false, new BigDecimal("10"),
                    new BigDecimal("42.00"), new BigDecimal("10"), null, Side.SELL);

            assertEquals(1, oms.mismatchedReportCount());
            assertEquals(0, totalShares(accounts).compareTo(shares), "balances untouched");
            assertEquals(0, totalCash(accounts).compareTo(cash), "balances untouched");
        });
    }

    @Test
    void aMatchedPairConservesCashAndShares(@TempDir java.nio.file.Path workDir) throws Exception {
        // The positive case: a buyer and a seller filled at the same price move value between them and
        // create none. This is the invariant the live system was violating.
        withOms(workDir, 47623, (oms, accounts) -> {
            BigDecimal sharesBefore = totalShares(accounts);
            BigDecimal cashBefore = totalCash(accounts);

            submit(oms, ACCOUNT_A, "A-1", Side.BUY, "40.00", "10");
            submit(oms, ACCOUNT_B, "B-1", Side.SELL, "40.00", "10");
            oms.onExecutionReport("A-1", "EXEC-1", "EX-1", true, false, new BigDecimal("10"),
                    new BigDecimal("40.00"), new BigDecimal("10"), null, Side.BUY);
            oms.onExecutionReport("B-1", "EXEC-2", "EX-2", true, false, new BigDecimal("10"),
                    new BigDecimal("40.00"), new BigDecimal("10"), null, Side.SELL);

            assertEquals(0, totalShares(accounts).compareTo(sharesBefore), "shares conserved");
            assertEquals(0, totalCash(accounts).compareTo(cashBefore), "cash conserved");
            assertEquals(0, held(accounts, ACCOUNT_A, "ACME", HoldingType.SHARE)
                    .compareTo(new BigDecimal("1010")));
            assertEquals(0, held(accounts, ACCOUNT_B, "ACME", HoldingType.SHARE)
                    .compareTo(new BigDecimal("990")));
        });
    }

    @Test
    void aReportForAnUnknownOrderIsIgnored(@TempDir java.nio.file.Path workDir) throws Exception {
        withOms(workDir, 47624, (oms, accounts) -> {
            BigDecimal shares = totalShares(accounts);
            oms.onExecutionReport("nope", "EXEC-1", "EX-1", true, false, new BigDecimal("10"),
                    new BigDecimal("42.00"), new BigDecimal("10"), null, Side.BUY);
            assertEquals(0, totalShares(accounts).compareTo(shares));
        });
    }
}
