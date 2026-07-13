-- FxcInvestor schema (MariaDB, database `fxc_investor`). Applied on startup by InvestorStore.
-- MariaDB is FxcInvestor's PRIMARY store (docs/DESIGN.md §4.4): agent config, decision log, and a
-- mirror of order/position history as reported over OFX.

CREATE TABLE IF NOT EXISTS schema_version (
    component   VARCHAR(32)  NOT NULL PRIMARY KEY,
    version     INT          NOT NULL,
    applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (component, version) VALUES ('fxc_investor', 1)
    ON DUPLICATE KEY UPDATE version = 1;

-- Every agent decision (submitted orders and skips), for audit and replay (Phase 4).
CREATE TABLE IF NOT EXISTS DECISION_LOG (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    created_at  BIGINT        NOT NULL,          -- epoch millis
    account     VARCHAR(64)   NOT NULL,
    symbol      VARCHAR(32)   NOT NULL,
    strategy    VARCHAR(32)   NOT NULL,
    side        VARCHAR(8),                      -- BUY/SELL; null when the strategy declined
    quantity    DECIMAL(28, 8),
    price       DECIMAL(20, 8),
    cl_ord_id   VARCHAR(64),
    status      VARCHAR(32)   NOT NULL           -- broker order status, or SKIPPED
);

-- ToDo (later): AGENT_CONFIG, ORDER_MIRROR, POSITION_MIRROR.
