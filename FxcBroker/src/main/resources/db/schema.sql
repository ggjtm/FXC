-- FxcBroker cold/archival schema (MariaDB, database `fxc_broker`). Applied on startup.
-- PHASE 0 STUB. The archive tables mirror the GridGain hot tables (docs/DESIGN.md §4.2):
-- ACCOUNT, POSITION, CLIENT_ORDER, EXECUTION. Their columns are defined alongside the hot-table
-- design in Phase 2 and the ArchiveService in Phase 5.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_broker', 0)
    ON DUPLICATE KEY UPDATE version = version;

-- ToDo (Phase 2/5): CLIENT_ORDER_ARCHIVE, EXECUTION_ARCHIVE.
