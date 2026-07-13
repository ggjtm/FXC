package com.fxc.pub.grid;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * FxcPub's GridGain hot projections (docs/DESIGN.md §4.3): {@code PUB_ACCOUNT}, {@code STATUS},
 * {@code FOLLOW}. These are in-memory read-models fed by the pubsub events FxcPub's XMPP-client
 * services observe/publish; the durable source of XMPP truth remains Tigase + MariaDB. The
 * {@code ArchiveService} (root Phase 5) drains aged statuses to the {@code fxc_pub} cold schema.
 */
public final class PubTables {

    static final String SQL_ENTRY_CACHE = "fxc-pub-sql-entry";

    private PubTables() {
    }

    public static void createAll(Ignite ignite) {
        IgniteCache<?, ?> sql = ignite.getOrCreateCache(SQL_ENTRY_CACHE);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS PUB_ACCOUNT (
                    jid          VARCHAR PRIMARY KEY,
                    display_name VARCHAR
                ) WITH "template=partitioned,backups=0"
                """);

        // A published status on a feed. `feed` is the feed owner (e.g. a broker id); `seq` orders
        // items within a feed.
        exec(sql, """
                CREATE TABLE IF NOT EXISTS STATUS (
                    status_id  VARCHAR PRIMARY KEY,
                    feed       VARCHAR NOT NULL,
                    author     VARCHAR,
                    body       VARCHAR NOT NULL,
                    created_at BIGINT NOT NULL,
                    seq        BIGINT NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        // Follow graph: `follower` subscribes to `feed`.
        exec(sql, """
                CREATE TABLE IF NOT EXISTS FOLLOW (
                    id       VARCHAR PRIMARY KEY,
                    follower VARCHAR NOT NULL,
                    feed     VARCHAR NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);
    }

    private static void exec(IgniteCache<?, ?> sql, String ddl) {
        sql.query(new SqlFieldsQuery(ddl)).getAll();
    }
}
