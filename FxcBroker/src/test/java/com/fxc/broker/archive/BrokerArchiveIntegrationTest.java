package com.fxc.broker.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.model.ClientOrder;
import com.fxc.broker.model.Execution;
import com.fxc.broker.model.OrderStatus;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Side;
import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 exit criteria (root PLAN) for FxcBroker: terminal client orders and their executions drain
 * from the GridGain hot tables to MariaDB (queryable there) while the hot tables stay bounded. Seeds
 * terminal + open orders directly through {@link BrokerRepository}, runs one archival pass, and
 * checks both sides.
 *
 * <p>Requires the MariaDB container; skips when 127.0.0.1:3306 is closed.
 */
class BrokerArchiveIntegrationTest {

    private static final String URL = "jdbc:mariadb://127.0.0.1:3306/fxc_broker";

    @Test
    void archivesTerminalOrdersAndExecutions(@TempDir java.nio.file.Path workDir) throws Exception {
        assumeTrue(reachable("127.0.0.1", 3306), "MariaDB not running on 127.0.0.1:3306 — skipping");

        try (ColdStore cold = ColdStore.open(URL, "fxc", "fxc", "test-broker-cold", "db/schema.sql")) {
            cleanArchiveTables(cold);

            try (GridNode node = GridNode.start("fxc-broker-arch", 47571, workDir.toString())) {
                BrokerTables.createAll(node.ignite());
                BrokerRepository repo = new BrokerRepository(node.ignite());

                // Two terminal orders (FILLED + CANCELLED) with executions, plus one live (ROUTED) order
                // that must NOT be archived.
                repo.upsertOrder(terminal("CO-1", OrderStatus.FILLED, "10", "42.10"));
                repo.insertExecution(new Execution("EX-1", "CO-1", "ACME", Side.BUY,
                        new BigDecimal("10"), new BigDecimal("42.10"), new BigDecimal("10"), OrderStatus.FILLED));
                repo.upsertOrder(terminal("CO-2", OrderStatus.CANCELLED, "5", "41.00"));
                repo.upsertOrder(open("CO-3"));

                ArchiveService archive = new ArchiveService(node.ignite(), cold, () -> 1_000L);
                assertEquals(2, archive.terminalOrderCount(), "two terminal orders should be hot pre-archive");

                ArchiveService.ArchiveResult result = archive.archiveNow();
                assertEquals(2, result.orders(), "should archive both terminal orders");
                assertEquals(1, result.executions(), "should archive the one execution of a terminal order");

                // Hot tables bounded: terminal rows drained, the live order remains.
                assertEquals(0, archive.terminalOrderCount(), "no terminal orders should remain hot");
                assertEquals(1, archive.hotCount("CLIENT_ORDER"), "the live order should remain hot");
                assertEquals(0, archive.hotCount("EXECUTION"), "the execution should be drained");

                // Rows are queryable in MariaDB.
                assertEquals(2, archiveRowCount(cold, "CLIENT_ORDER_ARCHIVE"), "orders archived to MariaDB");
                assertEquals(1, archiveRowCount(cold, "EXECUTION_ARCHIVE"), "executions archived to MariaDB");
                assertTrue(archivedAt(cold, "CLIENT_ORDER_ARCHIVE", "CO-1") == 1_000L, "archived_at stamped from clock");
            }
        }
    }

    private static ClientOrder terminal(String id, OrderStatus status, String qty, String px) {
        ClientOrder o = new ClientOrder(id, "000123456", "ACME", Side.BUY, OrderType.LIMIT,
                new BigDecimal(px), new BigDecimal(qty));
        o.applyFill(new BigDecimal(qty), new BigDecimal(px));
        o.setStatus(status);
        o.setExchangeOrderId("EOID-" + id);
        return o;
    }

    private static ClientOrder open(String id) {
        ClientOrder o = new ClientOrder(id, "000123456", "ACME", Side.BUY, OrderType.LIMIT,
                new BigDecimal("40.00"), new BigDecimal("7"));
        o.setStatus(OrderStatus.ROUTED);
        return o;
    }

    private static void cleanArchiveTables(ColdStore cold) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM CLIENT_ORDER_ARCHIVE");
            s.execute("DELETE FROM EXECUTION_ARCHIVE");
        }
    }

    private static long archiveRowCount(ColdStore cold, String table) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static long archivedAt(ColdStore cold, String table, String id) throws Exception {
        try (Connection c = cold.connection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT archived_at FROM " + table + " WHERE client_order_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
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
