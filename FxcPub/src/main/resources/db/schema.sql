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

INSERT INTO schema_version (component, version) VALUES ('fxc_pub', 1)
    ON DUPLICATE KEY UPDATE version = 1;

-- Aged statuses drained from the GridGain STATUS hot projection. Deep-history timeline reads fall
-- back here when the requested window predates the hot retention window (docs/DESIGN.md §4.3, §5).
CREATE TABLE IF NOT EXISTS STATUS_ARCHIVE (
    status_id   VARCHAR(96)  NOT NULL PRIMARY KEY,
    feed        VARCHAR(96)  NOT NULL,
    author      VARCHAR(96),
    body        VARCHAR(1024) NOT NULL,
    created_at  BIGINT       NOT NULL,
    seq         BIGINT       NOT NULL,
    archived_at BIGINT       NOT NULL,
    INDEX idx_status_archive_feed_seq (feed, seq)
);
