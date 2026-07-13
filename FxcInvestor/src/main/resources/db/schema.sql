-- FxcInvestor schema (MariaDB, database `fxc_investor`). Applied on startup.
-- PHASE 0 STUB. Unlike the GridGain components, MariaDB is FxcInvestor's PRIMARY store
-- (docs/DESIGN.md §4.4): agent config, decision log, and a mirror of order/position history as
-- reported over OFX. Table columns are defined in Phase 4.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_investor', 0)
    ON DUPLICATE KEY UPDATE version = version;

-- ToDo (Phase 4): AGENT_CONFIG, DECISION_LOG, ORDER_MIRROR, POSITION_MIRROR.
