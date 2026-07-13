-- FXC MariaDB bootstrap. Runs once, on first container start, via docker-entrypoint-initdb.d.
-- Creates one schema per component (cold/archival data) plus Tigase's own repository database.
-- Component tables are created by each component's own schema.sql; Tigase's tables are created by
-- its schema tool (see docs/DESIGN.md §3.2 and PLAN Phase 0).

-- Per-component schemas (cold / archival; FxcInvestor uses its schema as primary store too).
CREATE DATABASE IF NOT EXISTS fxc_pub       CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS fxc_broker    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS fxc_exchange  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS fxc_investor  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Application user shared by the FXC components (dev only).
CREATE USER IF NOT EXISTS 'fxc'@'%' IDENTIFIED BY 'fxc';
GRANT ALL PRIVILEGES ON fxc_pub.*      TO 'fxc'@'%';
GRANT ALL PRIVILEGES ON fxc_broker.*   TO 'fxc'@'%';
GRANT ALL PRIVILEGES ON fxc_exchange.* TO 'fxc'@'%';
GRANT ALL PRIVILEGES ON fxc_investor.* TO 'fxc'@'%';

-- Tigase's native JDBC repository (users, auth, offline, pubsub nodes/items).
CREATE DATABASE IF NOT EXISTS tigasedb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'tigase'@'%' IDENTIFIED BY 'tigase';
GRANT ALL PRIVILEGES ON tigasedb.* TO 'tigase'@'%';

FLUSH PRIVILEGES;
