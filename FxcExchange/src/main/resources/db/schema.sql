-- FxcExchange cold/archival schema (MariaDB, database `fxc_exchange`). Applied on startup.
-- PHASE 0 STUB. The archive tables mirror the GridGain hot tables (docs/DESIGN.md §4.1):
-- ORDERS, TRADE, SETTLEMENT_OBLIGATION (and INSTRUMENT reference data). Their columns are
-- defined alongside the hot-table design in Phase 1 and the ArchiveService in Phase 5.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_exchange', 0)
    ON DUPLICATE KEY UPDATE version = version;

-- ToDo (Phase 1/5): ORDERS_ARCHIVE, TRADE_ARCHIVE, SETTLEMENT_OBLIGATION_ARCHIVE.
