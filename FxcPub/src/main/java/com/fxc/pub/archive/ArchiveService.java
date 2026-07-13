package com.fxc.pub.archive;

import com.fxc.common.store.ColdStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.LongSupplier;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * Drains aged statuses from the FxcPub GridGain {@code STATUS} hot projection to the MariaDB cold
 * schema (docs/DESIGN.md §4.3, §5), keeping the hot table bounded while preserving deep history.
 * Statuses are append-only; a status is aged out once its {@code created_at} predates the retention
 * window ({@code now - retentionMs}). Deep-history timeline reads then fall back to MariaDB
 * (see {@link com.fxc.pub.service.TimelineService}).
 *
 * <p>Safe ordering: rows are written to MariaDB first, then deleted from GridGain by id — a MariaDB
 * failure never loses data, and a delete failure just re-archives next cycle (inserts are idempotent).
 */
public final class ArchiveService {

    private final IgniteCache<?, ?> sql;
    private final ColdStore cold;
    private final LongSupplier clock;
    private final long retentionMs;

    public ArchiveService(Ignite ignite, ColdStore cold, LongSupplier clock, long retentionMs) {
        this.sql = ignite.getOrCreateCache("fxc-pub-sql-entry");
        this.cold = cold;
        this.clock = clock;
        this.retentionMs = retentionMs;
    }

    /** Archive result counts. */
    public record ArchiveResult(int statuses) {
    }

    /** Run one archival pass: drain statuses older than the retention window. */
    public ArchiveResult archiveNow() {
        long now = clock.getAsLong();
        long cutoff = now - retentionMs;
        List<List<?>> rows = query(
                "SELECT status_id, feed, author, body, created_at, seq FROM STATUS WHERE created_at < ?", cutoff);
        if (rows.isEmpty()) {
            return new ArchiveResult(0);
        }
        String insert = "INSERT INTO STATUS_ARCHIVE "
                + "(status_id, feed, author, body, created_at, seq, archived_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE archived_at = VALUES(archived_at)";
        try (Connection conn = cold.connection(); PreparedStatement ps = conn.prepareStatement(insert)) {
            for (List<?> r : rows) {
                ps.setString(1, str(r, 0));
                ps.setString(2, str(r, 1));
                ps.setString(3, str(r, 2));
                ps.setString(4, str(r, 3));
                ps.setLong(5, lng(r, 4));
                ps.setLong(6, lng(r, 5));
                ps.setLong(7, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("Cold archive insert failed", e);
        }
        for (List<?> r : rows) {
            sql.query(new SqlFieldsQuery("DELETE FROM STATUS WHERE status_id = ?").setArgs(r.get(0))).getAll();
        }
        return new ArchiveResult(rows.size());
    }

    /** Count statuses currently resident in the hot GridGain table (diagnostics / bounded-ness checks). */
    public long hotStatusCount() {
        List<List<?>> rows = query("SELECT COUNT(*) FROM STATUS");
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get(0)).longValue();
    }

    private List<List<?>> query(String sqlText, Object... args) {
        return sql.query(new SqlFieldsQuery(sqlText).setArgs(args)).getAll();
    }

    private static String str(List<?> row, int i) {
        Object v = row.get(i);
        return v == null ? null : v.toString();
    }

    private static long lng(List<?> row, int i) {
        return ((Number) row.get(i)).longValue();
    }
}
