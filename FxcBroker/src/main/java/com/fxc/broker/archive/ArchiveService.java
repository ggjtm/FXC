package com.fxc.broker.archive;

import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.LongSupplier;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * Drains terminal client orders and their executions from the FxcBroker GridGain hot tables to the
 * MariaDB cold schema (root PLAN Phase 5), keeping the hot tables bounded. Terminal orders are
 * FILLED/CANCELLED/REJECTED; their executions are drained with them. Writes to MariaDB first, then
 * deletes from GridGain by id (no data loss on failure).
 */
public final class ArchiveService {

    private final IgniteCache<?, ?> sql;
    private final ColdStore cold;
    private final LongSupplier clock;

    public ArchiveService(Ignite ignite, ColdStore cold, LongSupplier clock) {
        this.sql = ignite.getOrCreateCache("fxc-broker-sql-entry");
        this.cold = cold;
        this.clock = clock;
    }

    public record ArchiveResult(int orders, int executions) {
    }

    public ArchiveResult archiveNow() {
        long now = clock.getAsLong();
        // Archive executions of terminal orders first, then the orders themselves.
        int executions = archiveExecutions(now);
        int orders = archiveOrders(now);
        return new ArchiveResult(orders, executions);
    }

    private int archiveOrders(long now) {
        List<List<?>> rows = query(
                "SELECT client_order_id, account_number, symbol, side, order_type, limit_price, quantity, "
                        + "cum_qty, avg_price, status, exchange_order_id, reject_reason "
                        + "FROM CLIENT_ORDER WHERE status IN ('FILLED', 'CANCELLED', 'REJECTED')");
        if (rows.isEmpty()) {
            return 0;
        }
        String insert = "INSERT INTO CLIENT_ORDER_ARCHIVE "
                + "(client_order_id, account_number, symbol, side, order_type, limit_price, quantity, cum_qty, "
                + "avg_price, status, exchange_order_id, reject_reason, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        insertBatch(insert, rows, (ps, r) -> {
            ps.setString(1, str(r, 0));
            ps.setString(2, str(r, 1));
            ps.setString(3, str(r, 2));
            ps.setString(4, str(r, 3));
            ps.setString(5, str(r, 4));
            ps.setBigDecimal(6, dec(r, 5));
            ps.setBigDecimal(7, dec(r, 6));
            ps.setBigDecimal(8, dec(r, 7));
            ps.setBigDecimal(9, dec(r, 8));
            ps.setString(10, str(r, 9));
            ps.setString(11, str(r, 10));
            ps.setString(12, str(r, 11));
            ps.setLong(13, now);
        });
        deleteById("CLIENT_ORDER", "client_order_id", rows);
        return rows.size();
    }

    private int archiveExecutions(long now) {
        List<List<?>> rows = query(
                "SELECT e.exec_id, e.client_order_id, e.symbol, e.side, e.last_qty, e.last_px, e.cum_qty, e.status "
                        + "FROM EXECUTION e JOIN CLIENT_ORDER o ON e.client_order_id = o.client_order_id "
                        + "WHERE o.status IN ('FILLED', 'CANCELLED', 'REJECTED')");
        if (rows.isEmpty()) {
            return 0;
        }
        String insert = "INSERT INTO EXECUTION_ARCHIVE "
                + "(exec_id, client_order_id, symbol, side, last_qty, last_px, cum_qty, status, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        insertBatch(insert, rows, (ps, r) -> {
            ps.setString(1, str(r, 0));
            ps.setString(2, str(r, 1));
            ps.setString(3, str(r, 2));
            ps.setString(4, str(r, 3));
            ps.setBigDecimal(5, dec(r, 4));
            ps.setBigDecimal(6, dec(r, 5));
            ps.setBigDecimal(7, dec(r, 6));
            ps.setString(8, str(r, 7));
            ps.setLong(9, now);
        });
        deleteById("EXECUTION", "exec_id", rows);
        return rows.size();
    }

    public long terminalOrderCount() {
        List<List<?>> rows = query(
                "SELECT COUNT(*) FROM CLIENT_ORDER WHERE status IN ('FILLED', 'CANCELLED', 'REJECTED')");
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get(0)).longValue();
    }

    public long hotCount(String table) {
        List<List<?>> rows = query("SELECT COUNT(*) FROM " + table);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get(0)).longValue();
    }

    // --- helpers ---

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps, List<?> row) throws SQLException;
    }

    private void insertBatch(String insertSql, List<List<?>> rows, Binder binder) {
        try (Connection conn = cold.connection(); PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (List<?> row : rows) {
                binder.bind(ps, row);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Cold archive insert failed", e);
        }
    }

    private void deleteById(String table, String idColumn, List<List<?>> rows) {
        for (List<?> row : rows) {
            sql.query(new SqlFieldsQuery("DELETE FROM " + table + " WHERE " + idColumn + " = ?")
                    .setArgs(row.get(0))).getAll();
        }
    }

    private List<List<?>> query(String sqlText) {
        return sql.query(new SqlFieldsQuery(sqlText)).getAll();
    }

    private static String str(List<?> row, int i) {
        Object v = row.get(i);
        return v == null ? null : v.toString();
    }

    private static BigDecimal dec(List<?> row, int i) {
        return (BigDecimal) row.get(i);
    }
}
