-- FxcPub cold/archival schema (MariaDB, database `fxc_pub`). Applied on startup.
-- PHASE 0 STUB. This is the FXC application archive only (aged statuses/timeline). It is SEPARATE
-- from Tigase's own repository (database `tigasedb`), which Tigase manages via its schema tool.
-- Hot projections (PUB_ACCOUNT, STATUS, FOLLOW) live in GridGain; deep-history reads fall back
-- here (docs/DESIGN.md §4.3, §5). Columns defined alongside Phase 3 / ArchiveService in Phase 5.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_pub', 0)
    ON DUPLICATE KEY UPDATE version = version;

-- ToDo (Phase 3/5): STATUS_ARCHIVE (aged statuses for deep-history timeline reads).
