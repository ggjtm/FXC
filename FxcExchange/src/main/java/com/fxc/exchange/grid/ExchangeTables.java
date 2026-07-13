package com.fxc.exchange.grid;

import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * The FxcExchange GridGain SQL schema (hot state, in-memory): {@code INSTRUMENT}, {@code ORDERS},
 * {@code TRADE}, {@code SETTLEMENT_OBLIGATION} (docs/DESIGN.md §4.1). All tables carry an
 * asset-class-agnostic shape; {@code INSTRUMENT} has an {@code ASSET_CLASS} discriminator.
 *
 * <p>These are the durable-for-the-node hot tables; the {@code ArchiveService} (Phase 5) drains
 * terminal rows to the MariaDB cold schema.
 */
public final class ExchangeTables {

    private static final String SQL_ENTRY_CACHE = "fxc-sql-entry";

    private ExchangeTables() {
    }

    /** Create all exchange tables if they do not already exist. Idempotent. */
    public static void createAll(Ignite ignite) {
        IgniteCache<?, ?> sql = ignite.getOrCreateCache(SQL_ENTRY_CACHE);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS INSTRUMENT (
                    symbol        VARCHAR PRIMARY KEY,
                    asset_class   VARCHAR NOT NULL,
                    base_ccy      VARCHAR,
                    quote_ccy     VARCHAR NOT NULL,
                    issuer        VARCHAR,
                    tick_size     DECIMAL(20, 8) NOT NULL,
                    lot_size      DECIMAL(20, 8) NOT NULL,
                    settle_style  VARCHAR NOT NULL,
                    settle_lag    INT NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS ORDERS (
                    order_id      VARCHAR PRIMARY KEY,
                    broker        VARCHAR NOT NULL,
                    symbol        VARCHAR NOT NULL,
                    side          VARCHAR NOT NULL,
                    order_type    VARCHAR NOT NULL,
                    limit_price   DECIMAL(20, 8),
                    quantity      DECIMAL(28, 8) NOT NULL,
                    cum_qty       DECIMAL(28, 8) NOT NULL,
                    status        VARCHAR NOT NULL,
                    sequence      BIGINT NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS TRADE (
                    trade_id       VARCHAR PRIMARY KEY,
                    symbol         VARCHAR NOT NULL,
                    price          DECIMAL(20, 8) NOT NULL,
                    quantity       DECIMAL(28, 8) NOT NULL,
                    buy_order_id   VARCHAR NOT NULL,
                    sell_order_id  VARCHAR NOT NULL,
                    buy_broker     VARCHAR NOT NULL,
                    sell_broker    VARCHAR NOT NULL,
                    aggressor      VARCHAR NOT NULL,
                    sequence       BIGINT NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);

        exec(sql, """
                CREATE TABLE IF NOT EXISTS SETTLEMENT_OBLIGATION (
                    id             VARCHAR PRIMARY KEY,
                    cycle          BIGINT NOT NULL,
                    broker         VARCHAR NOT NULL,
                    symbol         VARCHAR NOT NULL,
                    settle_style   VARCHAR NOT NULL,
                    deliver_ccy    VARCHAR,
                    deliver_amount DECIMAL(28, 8),
                    receive_ccy    VARCHAR,
                    receive_amount DECIMAL(28, 8),
                    quantity       DECIMAL(28, 8),
                    settle_lag     INT NOT NULL
                ) WITH "template=partitioned,backups=0"
                """);
    }

    private static void exec(IgniteCache<?, ?> sql, String ddl) {
        sql.query(new SqlFieldsQuery(ddl)).getAll();
    }

    /** Table names, for diagnostics / tests. */
    public static List<String> tableNames() {
        return List.of("INSTRUMENT", "ORDERS", "TRADE", "SETTLEMENT_OBLIGATION");
    }
}
