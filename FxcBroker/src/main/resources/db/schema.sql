-- FxcBroker cold/archival schema (MariaDB, database `fxc_broker`). Applied on startup.
-- The ArchiveService (Phase 5) drains terminal CLIENT_ORDERs and their EXECUTIONs from GridGain
-- here and removes them from the hot tables.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_broker', 1)
    ON DUPLICATE KEY UPDATE version = 1;

CREATE TABLE IF NOT EXISTS CLIENT_ORDER_ARCHIVE (
    client_order_id   VARCHAR(64)    NOT NULL PRIMARY KEY,
    account_number    VARCHAR(64)    NOT NULL,
    symbol            VARCHAR(32)    NOT NULL,
    side              VARCHAR(8)     NOT NULL,
    order_type        VARCHAR(8)     NOT NULL,
    limit_price       DECIMAL(20, 8),
    quantity          DECIMAL(28, 8) NOT NULL,
    cum_qty           DECIMAL(28, 8) NOT NULL,
    avg_price         DECIMAL(28, 8) NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    exchange_order_id VARCHAR(64),
    reject_reason     VARCHAR(255),
    archived_at       BIGINT         NOT NULL
);

CREATE TABLE IF NOT EXISTS EXECUTION_ARCHIVE (
    exec_id          VARCHAR(64)    NOT NULL PRIMARY KEY,
    client_order_id  VARCHAR(64)    NOT NULL,
    symbol           VARCHAR(32)    NOT NULL,
    side             VARCHAR(8)     NOT NULL,
    last_qty         DECIMAL(28, 8) NOT NULL,
    last_px          DECIMAL(20, 8) NOT NULL,
    cum_qty          DECIMAL(28, 8) NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    archived_at      BIGINT         NOT NULL
);
