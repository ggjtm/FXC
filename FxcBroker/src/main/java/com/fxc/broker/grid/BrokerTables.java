package com.fxc.broker.grid;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * The FxcBroker GridGain SQL schema (hot state, in-memory): {@code ACCOUNT}, {@code POSITION},
 * {@code CLIENT_ORDER}, {@code EXECUTION} (docs/DESIGN.md §4.2). The {@code ArchiveService}
 * (root Phase 5) drains terminal rows to the {@code fxc_broker} MariaDB cold schema.
 */
public final class BrokerTables {

    static final String SQL_ENTRY_CACHE = "fxc-broker-sql-entry";

    private BrokerTables() {
    }

    public static void createAll(Ignite ignite) {
        IgniteCache<?, ?> sql = ignite.getOrCreateCache(SQL_ENTRY_CACHE);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS ACCOUNT (
                    account_number VARCHAR PRIMARY KEY,
                    owner_name     VARCHAR,
                    base_ccy       VARCHAR NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        // Unified positions: holding_type discriminates CASH vs SHARE (docs/DESIGN.md §3.0).
        exec(sql, """
                CREATE TABLE IF NOT EXISTS POSITION (
                    pos_key       VARCHAR PRIMARY KEY,
                    account_number VARCHAR NOT NULL,
                    holding_type  VARCHAR NOT NULL,
                    instrument    VARCHAR NOT NULL,
                    quantity      DECIMAL(28, 8) NOT NULL,
                    avg_price     DECIMAL(28, 8) NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS CLIENT_ORDER (
                    client_order_id  VARCHAR PRIMARY KEY,
                    account_number   VARCHAR NOT NULL,
                    symbol           VARCHAR NOT NULL,
                    side             VARCHAR NOT NULL,
                    order_type       VARCHAR NOT NULL,
                    limit_price      DECIMAL(20, 8),
                    quantity         DECIMAL(28, 8) NOT NULL,
                    cum_qty          DECIMAL(28, 8) NOT NULL,
                    avg_price        DECIMAL(28, 8) NOT NULL,
                    status           VARCHAR NOT NULL,
                    exchange_order_id VARCHAR,
                    reject_reason    VARCHAR
                ) WITH "template=partitioned,backups=0"
                """);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS EXECUTION (
                    exec_id          VARCHAR PRIMARY KEY,
                    client_order_id  VARCHAR NOT NULL,
                    symbol           VARCHAR NOT NULL,
                    side             VARCHAR NOT NULL,
                    last_qty         DECIMAL(28, 8) NOT NULL,
                    last_px          DECIMAL(20, 8) NOT NULL,
                    cum_qty          DECIMAL(28, 8) NOT NULL,
                    status           VARCHAR NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);
    }

    private static void exec(IgniteCache<?, ?> sql, String ddl) {
        sql.query(new SqlFieldsQuery(ddl)).getAll();
    }
}
