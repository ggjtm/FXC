package com.fxc.broker.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.md.MarketDataCache;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.Position;
import com.fxc.broker.pnl.PnlService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Agents opening their own accounts (docs/stories/004).
 *
 * <p>The load-bearing property is <b>idempotence per client id</b>: agents restart and Locust
 * investors respawn into the same slot, and a second account for the same trader would fragment its
 * P&amp;L and strand a funded balance nobody is trading. The rest is funding — an opened account must
 * start exactly like a seeded one, or the console's curves would not be comparable.
 */
class AccountOpeningTest {

    /** Cash only: the simplest policy that cannot inflate the float. */
    private static final AccountOpeningPolicy POLICY = new AccountOpeningPolicy(
            true, "USD", new BigDecimal("1000000"), List.of("ARVX"), BigDecimal.ZERO,
            new BigDecimal("42.00"), null, 100_000L, 9);

    /** Minting share seeding (no source account) — still supported, still inflates the float. */
    private static final AccountOpeningPolicy WITH_SHARES = new AccountOpeningPolicy(
            true, "USD", new BigDecimal("1000000"), List.of("ARVX"), new BigDecimal("1000"),
            new BigDecimal("42.00"), null, 100_000L, 9);

    /** The shipped policy: shares drawn FROM the issuer, so the total never moves. */
    private static final AccountOpeningPolicy FROM_ISSUER = new AccountOpeningPolicy(
            true, "USD", new BigDecimal("1000000"), List.of("ARVX"), new BigDecimal("100"),
            new BigDecimal("42.00"), "000123456", 100_000L, 9);

    private interface Scenario {
        void run(AccountService accounts, BrokerRepository repository);
    }

    private static void withAccounts(java.nio.file.Path workDir, int discoveryPort,
                                     AccountOpeningPolicy policy, Scenario scenario) throws Exception {
        try (GridNode node = GridNode.start("fxc-broker-open-" + discoveryPort, discoveryPort,
                workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            BrokerRepository repository = new BrokerRepository(node.ignite());
            AccountService accounts = new AccountService(repository);
            accounts.configureOpening(policy);
            scenario.run(accounts, repository);
        }
    }

    private static BigDecimal cash(AccountService accounts, String account, String currency) {
        for (Position position : accounts.positions(account)) {
            if (position.holdingType() == HoldingType.CASH && position.instrument().equals(currency)) {
                return position.quantity();
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal shares(AccountService accounts, String account, String symbol) {
        for (Position position : accounts.positions(account)) {
            if (position.holdingType() == HoldingType.SHARE && position.instrument().equals(symbol)) {
                return position.quantity();
            }
        }
        return BigDecimal.ZERO;
    }

    @Test
    void opensAnAccountWithCashAndNoShares(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47610, POLICY, (accounts, repository) -> {
            AccountService.OpenResult result = accounts.openAccount("investor-a", "Investor A");

            assertTrue(result.opened());
            assertTrue(accounts.accountExists(result.account()));
            // Same purchasing power as every other agent, so the console's curves are comparable.
            assertEquals(0, new BigDecimal("1000000").compareTo(cash(accounts, result.account(), "USD")));
            // And no shares: an opened account that carried stock would mint it (docs/PROBLEMS.md P19).
            assertEquals(0, BigDecimal.ZERO.compareTo(shares(accounts, result.account(), "ARVX")));
        });
    }

    @Test
    void anIssuerPlacesTheFloatWithoutCreatingMore(@TempDir java.nio.file.Path workDir) throws Exception {
        // The startup model (docs/stories/006): shares exist once, at the issuer, and are moved to the
        // market makers. Seeding each maker instead would create them — which is how a demo ended up
        // with 260,000 shares against a 2,000-share float (docs/PROBLEMS.md P19).
        withAccounts(workDir, 47626, POLICY, (accounts, repository) -> {
            accounts.seedAccount("000000001", "FXC Issuer", "USD", Map.of());
            accounts.seedShares("000000001", "ARVX", new BigDecimal("1000000"), new BigDecimal("42.00"));
            for (String maker : List.of("000123456", "000654321")) {
                accounts.seedAccount(maker, "Market Maker", "USD",
                        Map.of("USD", new BigDecimal("1000000")));
                accounts.transferShares("000000001", maker, "ARVX", new BigDecimal("500000"));
            }

            assertEquals(0, BigDecimal.ZERO.compareTo(shares(accounts, "000000001", "ARVX")),
                    "the issuer placed all of it");
            assertEquals(0, new BigDecimal("500000").compareTo(shares(accounts, "000123456", "ARVX")));
            assertEquals(0, new BigDecimal("500000").compareTo(shares(accounts, "000654321", "ARVX")));
            // The invariant that matters: the total is exactly what was issued.
            BigDecimal total = shares(accounts, "000000001", "ARVX")
                    .add(shares(accounts, "000123456", "ARVX"))
                    .add(shares(accounts, "000654321", "ARVX"));
            assertEquals(0, new BigDecimal("1000000").compareTo(total));
        });
    }

    @Test
    void theFloatCannotBeOverAllocated(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47627, POLICY, (accounts, repository) -> {
            accounts.seedAccount("000000001", "FXC Issuer", "USD", Map.of());
            accounts.seedShares("000000001", "ARVX", new BigDecimal("1000"), new BigDecimal("42.00"));
            accounts.seedAccount("000123456", "Market Maker", "USD", Map.of("USD", BigDecimal.ONE));
            assertThrows(IllegalArgumentException.class, () ->
                    accounts.transferShares("000000001", "000123456", "ARVX", new BigDecimal("1001")));
        });
    }

    @Test
    void openingAccountsDoesNotChangeTheFloat(@TempDir java.nio.file.Path workDir) throws Exception {
        // The invariant the demo was violating: investors bring capital, not stock. The tradable float
        // is whatever the seeded accounts hold, and it must not move when agents arrive.
        withAccounts(workDir, 47619, POLICY, (accounts, repository) -> {
            accounts.seedAccount("000123456", "Dev", "USD", Map.of("USD", new BigDecimal("1000000")));
            accounts.seedShares("000123456", "ARVX", new BigDecimal("1000"), new BigDecimal("42.00"));
            BigDecimal floatBefore = shares(accounts, "000123456", "ARVX");

            BigDecimal opened = BigDecimal.ZERO;
            for (int i = 0; i < 20; i++) {
                String account = accounts.openAccount("locust-" + i, null).account();
                opened = opened.add(shares(accounts, account, "ARVX"));
            }

            assertEquals(0, BigDecimal.ZERO.compareTo(opened), "20 investors minted no stock");
            assertEquals(0, floatBefore.compareTo(shares(accounts, "000123456", "ARVX")),
                    "and the float is untouched");
        });
    }

    @Test
    void openingAccountsFromTheIssuerConservesTheFloat(@TempDir java.nio.file.Path workDir)
            throws Exception {
        // The stronger form of the invariant above, and the one the demo actually ships: opened
        // accounts DO carry stock (25 books need two sides from tick one — fxc/docs/PROBLEMS.md
        // P24), but every share is transferred off the issuer rather than minted, so the market-wide
        // total is exactly what was issued no matter how many investors arrive.
        withAccounts(workDir, 47620, FROM_ISSUER, (accounts, repository) -> {
            accounts.seedAccount("000123456", "Issuer", "USD", Map.of("USD", new BigDecimal("1000000")));
            accounts.seedShares("000123456", "ARVX", new BigDecimal("5000"), new BigDecimal("42.00"));
            BigDecimal totalBefore = shares(accounts, "000123456", "ARVX");

            BigDecimal held = BigDecimal.ZERO;
            for (int i = 0; i < 20; i++) {
                String account = accounts.openAccount("locust-" + i, null).account();
                held = held.add(shares(accounts, account, "ARVX"));
            }

            assertEquals(0, new BigDecimal("2000").compareTo(held), "20 investors x 100 shares each");
            assertEquals(0, totalBefore.compareTo(shares(accounts, "000123456", "ARVX").add(held)),
                    "issuer + investors is still exactly the issued float");
        });
    }

    @Test
    void shareSeedingRemainsAvailableWhenAskedFor(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47625, WITH_SHARES, (accounts, repository) -> {
            String account = accounts.openAccount("investor-a", null).account();
            assertEquals(0, new BigDecimal("1000").compareTo(shares(accounts, account, "ARVX")));
        });
    }

    @Test
    void theSameClientIdGetsTheSameAccount(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47611, POLICY, (accounts, repository) -> {
            AccountService.OpenResult first = accounts.openAccount("locust-3", null);
            AccountService.OpenResult again = accounts.openAccount("locust-3", null);

            assertEquals(first.account(), again.account());
            assertTrue(first.opened());
            assertFalse(again.opened(), "the second call found the account rather than minting one");
        });
    }

    @Test
    void differentClientsGetDifferentAccounts(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47612, POLICY, (accounts, repository) -> {
            String a = accounts.openAccount("locust-0", null).account();
            String b = accounts.openAccount("locust-1", null).account();
            String c = accounts.openAccount("investor-b", null).account();

            assertNotEquals(a, b);
            assertNotEquals(b, c);
            assertEquals(3, List.of(a, b, c).stream().distinct().count());
        });
    }

    @Test
    void numbersStartAboveTheSeededAccountsAndKeepTheirWidth(@TempDir java.nio.file.Path workDir)
            throws Exception {
        withAccounts(workDir, 47613, POLICY, (accounts, repository) -> {
            // Seeded dev accounts exist first, exactly as they do at broker startup.
            accounts.seedAccount("000123456", "Dev", "USD", Map.of("USD", BigDecimal.TEN));

            String opened = accounts.openAccount("investor-a", null).account();

            assertEquals(9, opened.length(), "same width as the seeded accounts: " + opened);
            assertTrue(opened.chars().allMatch(Character::isDigit));
            // max("000123456") + 1 — the number says nothing was overwritten.
            assertEquals("000123457", opened);
        });
    }

    @Test
    void anOpenedAccountCanTradeImmediately(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47614, POLICY, (accounts, repository) -> {
            String account = accounts.openAccount("investor-a", null).account();
            // The pre-trade check is where an unfunded or unknown account would surface.
            assertTrue(accounts.check(account, com.fxc.common.instrument.InstrumentCatalog.bySymbol()
                            .get("ARVX"), com.fxc.broker.model.Side.BUY, new BigDecimal("42.00"),
                    new BigDecimal("10")).isEmpty());
        });
    }

    @Test
    void openingIsRefusedWhenDisabled(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47615, AccountOpeningPolicy.disabled(), (accounts, repository) ->
                assertThrows(IllegalStateException.class, () -> accounts.openAccount("locust-0", null)));
    }

    @Test
    void aBlankClientIdIsRefused(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47616, POLICY, (accounts, repository) -> {
            assertThrows(IllegalArgumentException.class, () -> accounts.openAccount("", null));
            assertThrows(IllegalArgumentException.class, () -> accounts.openAccount(null, null));
        });
    }

    @Test
    void listenersSeeEveryOpenedAccountOnce(@TempDir java.nio.file.Path workDir) throws Exception {
        withAccounts(workDir, 47617, POLICY, (accounts, repository) -> {
            List<String> opened = new ArrayList<>();
            accounts.addAccountOpenedListener((account, owner) -> opened.add(account));

            String first = accounts.openAccount("locust-0", null).account();
            accounts.openAccount("locust-0", null);   // already open: nothing to announce
            String second = accounts.openAccount("locust-1", null).account();

            assertEquals(List.of(first, second), opened);
        });
    }

    @Test
    void pnlBaselinesAnAccountOpenedMidSession(@TempDir java.nio.file.Path workDir) throws Exception {
        // Without this the curve would start at the account's first fill, showing that one trade as
        // the entire session's move.
        try (GridNode node = GridNode.start("fxc-broker-open-47618", 47618, workDir.toString())) {
            BrokerTables.createAll(node.ignite());
            AccountService accounts = new AccountService(new BrokerRepository(node.ignite()));
            accounts.configureOpening(POLICY);
            PnlService pnl = new PnlService(accounts, new MarketDataCache(), () -> 1_000L);
            pnl.captureBaselines();                       // no accounts yet
            accounts.addAccountOpenedListener(pnl);

            String account = accounts.openAccount("investor-a", "Investor A").account();

            PnlService.AccountPnl p = pnl.series().stream()
                    .filter(row -> row.account().equals(account)).findFirst().orElseThrow();
            // Cash only, so the baseline is exactly the opening balance.
            assertEquals(0, new BigDecimal("1000000").compareTo(p.baseline()));
            assertEquals(0, BigDecimal.ZERO.compareTo(p.relative()));
            assertEquals(1, p.points().size(), "anchored at zero, like a seeded account");
        }
    }
}
