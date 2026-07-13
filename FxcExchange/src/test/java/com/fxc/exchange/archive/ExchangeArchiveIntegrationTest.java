package com.fxc.exchange.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.common.instrument.InstrumentCatalog;
import com.fxc.common.store.ColdStore;
import com.fxc.exchange.book.NewOrder;
import com.fxc.exchange.book.OrderType;
import com.fxc.exchange.book.Side;
import com.fxc.exchange.fix.ExchangeServer;
import com.fxc.exchange.service.MatchingEngineService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.SessionSettings;

/**
 * Phase 5 exit criteria (root PLAN): under trading, terminal rows drain from the GridGain hot tables
 * to MariaDB (queryable there) and the hot tables stay bounded. Drives crossing + cancel + clearing
 * directly through the services, then runs an archival pass and checks both sides.
 *
 * <p>Requires the MariaDB container; skips when 127.0.0.1:3306 is closed.
 */
class ExchangeArchiveIntegrationTest {

    private static final String URL = "jdbc:mariadb://127.0.0.1:3306/fxc_exchange";

    @Test
    void archivesTerminalRowsToMariaDbAndBoundsHotTables(@TempDir java.nio.file.Path workDir) throws Exception {
        assumeTrue(reachable("127.0.0.1", 3306), "MariaDB not running on 127.0.0.1:3306 — skipping");

        try (ColdStore cold = ColdStore.open(URL, "fxc", "fxc", "test-cold", "db/schema.sql")) {
            cleanArchiveTables(cold);

            try (ExchangeServer exchange = ExchangeServer.start(
                    acceptorSettings(freePort()), "fxc-exchange-arch", 47561,
                    workDir.toString(), InstrumentCatalog.defaults(), cold, 0 /* manual archive */)) {

                MatchingEngineService mes = exchange.matchingService();

                // A crossing pair (both FILLED) + one order we cancel (CANCELLED) → 3 terminal orders,
                // 1 trade; then a clearing cycle → settlement obligations.
                mes.submit(order("AS1", "mm", Side.SELL, "42.10", 100));
                mes.submit(order("AB1", "tk", Side.BUY, "42.10", 100));
                mes.submit(order("AC1", "tk", Side.BUY, "42.00", 100));
                mes.cancel("AC1");
                exchange.clearingService().runCycle(1);

                ArchiveService archive = exchange.archiveService();
                assertTrue(archive.terminalOrderCount() >= 3, "terminal orders should be in the hot table pre-archive");

                ArchiveService.ArchiveResult result = archive.archiveNow();

                assertTrue(result.orders() >= 3, "should archive >=3 terminal orders, got " + result.orders());
                assertTrue(result.trades() >= 1, "should archive >=1 trade, got " + result.trades());
                assertTrue(result.settlements() >= 1, "should archive >=1 settlement, got " + result.settlements());

                // Hot tables bounded: terminal orders + trades + settlements drained from GridGain.
                assertEquals(0, archive.terminalOrderCount(), "no terminal orders should remain hot");
                assertEquals(0, archive.hotCount("TRADE"), "trades should be drained from the hot table");
                assertEquals(0, archive.hotCount("SETTLEMENT_OBLIGATION"), "settlements should be drained");

                // Rows are queryable in MariaDB.
                assertTrue(archiveRowCount(cold, "ORDERS_ARCHIVE") >= 3, "orders archived to MariaDB");
                assertTrue(archiveRowCount(cold, "TRADE_ARCHIVE") >= 1, "trades archived to MariaDB");
                assertTrue(archiveRowCount(cold, "SETTLEMENT_OBLIGATION_ARCHIVE") >= 1, "settlements archived to MariaDB");
            }
        }
    }

    private static NewOrder order(String id, String broker, Side side, String price, int qty) {
        return new NewOrder(id, broker, "ACME", side, OrderType.LIMIT, new BigDecimal(price), new BigDecimal(qty));
    }

    private static void cleanArchiveTables(ColdStore cold) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM ORDERS_ARCHIVE");
            s.execute("DELETE FROM TRADE_ARCHIVE");
            s.execute("DELETE FROM SETTLEMENT_OBLIGATION_ARCHIVE");
        }
    }

    private static long archiveRowCount(ColdStore cold, String table) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0;
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

    private static SessionSettings acceptorSettings(int port) throws Exception {
        String cfg = """
                [DEFAULT]
                ConnectionType=acceptor
                BeginString=FIX.4.4
                SenderCompID=EXCHANGE
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=30
                SocketAcceptPort=%d

                [SESSION]
                TargetCompID=BROKER1
                """.formatted(port);
        return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
    }
}
