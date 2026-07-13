package com.fxc.pub.grid;

import com.fxc.pub.service.StatusRecord;
import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/** Reads/writes FxcPub hot projections in the GridGain SQL tables ({@link PubTables}). */
public final class PubRepository {

    private final IgniteCache<?, ?> sql;

    public PubRepository(Ignite ignite) {
        this.sql = ignite.getOrCreateCache(PubTables.SQL_ENTRY_CACHE);
    }

    public void upsertAccount(String jid, String displayName) {
        run("MERGE INTO PUB_ACCOUNT (jid, display_name) VALUES (?, ?)", jid, displayName);
    }

    public void addFollow(String follower, String feed) {
        run("MERGE INTO FOLLOW (id, follower, feed) VALUES (?, ?, ?)", follower + "|" + feed, follower, feed);
    }

    public void insertStatus(StatusRecord s) {
        run("MERGE INTO STATUS (status_id, feed, author, body, created_at, seq) VALUES (?, ?, ?, ?, ?, ?)",
                s.statusId(), s.feed(), s.author(), s.body(), s.createdAt(), s.seq());
    }

    /** Most recent statuses on a feed, newest first, up to {@code limit}. */
    public List<StatusRecord> recentStatuses(String feed, int limit) {
        List<List<?>> rows = sql.query(new SqlFieldsQuery(
                "SELECT status_id, feed, author, body, created_at, seq FROM STATUS "
                        + "WHERE feed = ? ORDER BY seq DESC LIMIT ?").setArgs(feed, limit)).getAll();
        List<StatusRecord> out = new ArrayList<>();
        for (List<?> r : rows) {
            out.add(new StatusRecord((String) r.get(0), (String) r.get(1), (String) r.get(2),
                    (String) r.get(3), (Long) r.get(4), (Long) r.get(5)));
        }
        return out;
    }

    private void run(String dml, Object... args) {
        sql.query(new SqlFieldsQuery(dml).setArgs(args)).getAll();
    }
}
