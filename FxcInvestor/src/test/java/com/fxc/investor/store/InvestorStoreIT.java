package com.fxc.investor.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.net.Socket;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the MariaDB decision-log persistence (docs/DESIGN.md §4.4): applies the schema, appends
 * decisions, and reads them back. Requires the MariaDB container (from {@code docker compose up});
 * skips when 127.0.0.1:3306 is closed.
 */
class InvestorStoreIT {

    private static final String URL = "jdbc:mariadb://127.0.0.1:3306/fxc_investor";
    private static final String USER = "fxc";
    private static final String PASSWORD = "fxc";

    @Test
    void logsAndReadsBackDecisions() {
        assumeTrue(reachable("127.0.0.1", 3306), "MariaDB not running on 127.0.0.1:3306 — skipping");

        // Unique account so the test only sees its own rows.
        String account = "IT-" + System.nanoTime();

        try (InvestorStore store = InvestorStore.open(URL, USER, PASSWORD)) {
            store.logDecision(new DecisionRecord(1000L, account, "ACME", "rando",
                    null, null, null, null, DecisionRecord.SKIPPED));
            store.logDecision(new DecisionRecord(2000L, account, "ACME", "booker",
                    "BUY", new BigDecimal("5"), new BigDecimal("42.11"), "INV-1", "ROUTED"));

            List<DecisionRecord> recent = store.recent(account, 10);
            assertEquals(2, recent.size(), "both decisions should be persisted");

            // Newest first: the BUY (id higher) then the SKIPPED.
            DecisionRecord newest = recent.get(0);
            assertEquals("booker", newest.strategy());
            assertEquals("BUY", newest.side());
            assertEquals(0, newest.quantity().compareTo(new BigDecimal("5")));
            assertEquals(0, newest.price().compareTo(new BigDecimal("42.11")));
            assertEquals("INV-1", newest.clOrdId());
            assertEquals("ROUTED", newest.status());

            DecisionRecord skipped = recent.get(1);
            assertEquals(DecisionRecord.SKIPPED, skipped.status());
            assertEquals("rando", skipped.strategy());
            assertNull(skipped.side(), "a skip has no side");
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
