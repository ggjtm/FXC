package com.fxc.exchange.archive;

import com.fxc.common.store.ColdStore;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * Drains terminal/aged rows from the FxcExchange GridGain hot tables to the MariaDB cold schema
 * (docs/DESIGN.md §3.2, root PLAN Phase 5), then removes them from GridGain so the in-memory hot
 * tables stay bounded under sustained trading. Terminal orders (FILLED/CANCELLED/REJECTED) plus all
 * trades and settlement obligations (append-only history) are archived.
 *
 * <p>Safe ordering: rows are written to MariaDB first, then deleted from GridGain by id — so a
 * MariaDB failure never loses data (nothing is deleted), and a delete failure just re-archives next
 * cycle (the archive inserts are idempotent).
 */
public final class ArchiveService {

    private final IgniteCache<?, ?> sql;
    private final ColdStore cold;
    private final java.util.function.LongSupplier clock;

    public ArchiveService(Ignite ignite, ColdStore cold, java.util.function.LongSupplier clock) {
        this.sql = ignite.getOrCreateCache("fxc-sql-entry");
        this.cold = cold;
        this.clock = clock;
    }

    /** Archive result counts. */
    public record ArchiveResult(int orders, int trades, int settlements) {
        int total() {
            return orders + trades + settlements;
        }
    }

    /** Run one archival pass. */
    public ArchiveResult archiveNow() {
        long now = clock.getAsLong();
        int orders = archiveOrders(now);
        int trades = archiveTrades(now);
        int settlements = archiveSettlements(now);
        return new ArchiveResult(orders, trades, settlements);
    }

    private int archiveOrders(long now) {
        List<List<?>> rows = query(
                "SELECT order_id, broker, symbol, side, order_type, limit_price, quantity, cum_qty, status, sequence "
                        + "FROM ORDERS WHERE status IN ('FILLED', 'CANCELLED', 'REJECTED')");
        if (rows.isEmpty()) {
            return 0;
        }
        String insert = "INSERT INTO ORDERS_ARCHIVE "
                + "(order_id, broker, symbol, side, order_type, limit_price, quantity, cum_qty, status, sequence, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        insertBatch(insert, rows, (ps, r) -> {
            ps.setString(1, str(r, 0));
            ps.setString(2, str(r, 1));
            ps.setString(3, str(r, 2));
            ps.setString(4, str(r, 3));
            ps.setString(5, str(r, 4));
            ps.setBigDecimal(6, dec(r, 5));
            ps.setBigDecimal(7, dec(r, 6));
            ps.setBigDecimal(8, dec(r, 7));
            ps.setString(9, str(r, 8));
            ps.setLong(10, lng(r, 9));
            ps.setLong(11, now);
        });
        deleteById("ORDERS", "order_id", rows);
        return rows.size();
    }

    private int archiveTrades(long now) {
        List<List<?>> rows = query(
                "SELECT trade_id, symbol, price, quantity, buy_order_id, sell_order_id, buy_broker, sell_broker, aggressor, sequence "
                        + "FROM TRADE");
        if (rows.isEmpty()) {
            return 0;
        }
        String insert = "INSERT INTO TRADE_ARCHIVE "
                + "(trade_id, symbol, price, quantity, buy_order_id, sell_order_id, buy_broker, sell_broker, aggressor, sequence, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        insertBatch(insert, rows, (ps, r) -> {
            ps.setString(1, str(r, 0));
            ps.setString(2, str(r, 1));
            ps.setBigDecimal(3, dec(r, 2));
            ps.setBigDecimal(4, dec(r, 3));
            ps.setString(5, str(r, 4));
            ps.setString(6, str(r, 5));
            ps.setString(7, str(r, 6));
            ps.setString(8, str(r, 7));
            ps.setString(9, str(r, 8));
            ps.setLong(10, lng(r, 9));
            ps.setLong(11, now);
        });
        deleteById("TRADE", "trade_id", rows);
        return rows.size();
    }

    private int archiveSettlements(long now) {
        List<List<?>> rows = query(
                "SELECT id, cycle, broker, symbol, settle_style, deliver_ccy, deliver_amount, receive_ccy, receive_amount, quantity, settle_lag "
                        + "FROM SETTLEMENT_OBLIGATION");
        if (rows.isEmpty()) {
            return 0;
        }
        String insert = "INSERT INTO SETTLEMENT_OBLIGATION_ARCHIVE "
                + "(id, cycle, broker, symbol, settle_style, deliver_ccy, deliver_amount, receive_ccy, receive_amount, quantity, settle_lag, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        insertBatch(insert, rows, (ps, r) -> {
            ps.setString(1, str(r, 0));
            ps.setLong(2, lng(r, 1));
            ps.setString(3, str(r, 2));
            ps.setString(4, str(r, 3));
            ps.setString(5, str(r, 4));
            ps.setString(6, str(r, 5));
            ps.setBigDecimal(7, dec(r, 6));
            ps.setString(8, str(r, 7));
            ps.setBigDecimal(9, dec(r, 8));
            ps.setBigDecimal(10, dec(r, 9));
            ps.setInt(11, (int) lng(r, 10));
            ps.setLong(12, now);
        });
        deleteById("SETTLEMENT_OBLIGATION", "id", rows);
        return rows.size();
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

    /** Delete the archived rows from GridGain by primary key (only after they are safely in MariaDB). */
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

    private static long lng(List<?> row, int i) {
        return ((Number) row.get(i)).longValue();
    }

    /** Count rows currently in a hot GridGain table (for bounded-ness checks / diagnostics). */
    public long hotCount(String table) {
        List<List<?>> rows = query("SELECT COUNT(*) FROM " + table);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get(0)).longValue();
    }

    /** Count terminal orders still resident in the hot ORDERS table (should be 0 after a pass). */
    public long terminalOrderCount() {
        List<List<?>> rows = query(
                "SELECT COUNT(*) FROM ORDERS WHERE status IN ('FILLED', 'CANCELLED', 'REJECTED')");
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get(0)).longValue();
    }
}
