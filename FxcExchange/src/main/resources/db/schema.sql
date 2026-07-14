-- FxcExchange cold/archival schema (MariaDB, database `fxc_exchange`). Applied on startup.
-- The archive tables mirror the GridGain hot tables (docs/DESIGN.md §4.1). The ArchiveService
-- (Phase 5) drains terminal orders + trades + settlement obligations here and removes them from
-- GridGain, keeping the hot tables bounded.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_exchange', 1)
    ON DUPLICATE KEY UPDATE version = 1;

CREATE TABLE IF NOT EXISTS ORDERS_ARCHIVE (
    order_id      VARCHAR(64)    NOT NULL PRIMARY KEY,
    broker        VARCHAR(64)    NOT NULL,
    symbol        VARCHAR(32)    NOT NULL,
    side          VARCHAR(8)     NOT NULL,
    order_type    VARCHAR(8)     NOT NULL,
    limit_price   DECIMAL(20, 8),
    quantity      DECIMAL(28, 8) NOT NULL,
    cum_qty       DECIMAL(28, 8) NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    sequence      BIGINT         NOT NULL,
    archived_at   BIGINT         NOT NULL
);

CREATE TABLE IF NOT EXISTS TRADE_ARCHIVE (
    trade_id       VARCHAR(64)    NOT NULL PRIMARY KEY,
    symbol         VARCHAR(32)    NOT NULL,
    price          DECIMAL(20, 8) NOT NULL,
    quantity       DECIMAL(28, 8) NOT NULL,
    buy_order_id   VARCHAR(64)    NOT NULL,
    sell_order_id  VARCHAR(64)    NOT NULL,
    buy_broker     VARCHAR(64)    NOT NULL,
    sell_broker    VARCHAR(64)    NOT NULL,
    aggressor      VARCHAR(8)     NOT NULL,
    ts             BIGINT         NOT NULL,
    sequence       BIGINT         NOT NULL,
    archived_at    BIGINT         NOT NULL,
    INDEX idx_trade_archive_symbol_ts (symbol, ts)
);

-- Migration for dev DBs created before the feed/candle story added the trade time axis.
ALTER TABLE TRADE_ARCHIVE ADD COLUMN IF NOT EXISTS ts BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS SETTLEMENT_OBLIGATION_ARCHIVE (
    id             VARCHAR(96)    NOT NULL PRIMARY KEY,
    cycle          BIGINT         NOT NULL,
    broker         VARCHAR(64)    NOT NULL,
    symbol         VARCHAR(32)    NOT NULL,
    settle_style   VARCHAR(32)    NOT NULL,
    deliver_ccy    VARCHAR(8),
    deliver_amount DECIMAL(28, 8),
    receive_ccy    VARCHAR(8),
    receive_amount DECIMAL(28, 8),
    quantity       DECIMAL(28, 8),
    settle_lag     INT            NOT NULL,
    archived_at    BIGINT         NOT NULL
);
