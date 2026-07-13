# FXC Implementation Plan

Status: **Phase 4 in progress** (FxcInvestor: Strategy SPI + pluggable price-sampler seam, the
`rando` agent, OFX client, and single-instance runner — trades end-to-end over OFX and fills; 35
tests green total. `booker`/`bookfish` agents + the Gatling multi-agent runner are specified as
stories in FxcInvestor/docs/stories/). Phases 0–3 complete.
Companion to [DESIGN.md](DESIGN.md).

Phases are ordered so every phase ends with something runnable and testable. Exchange comes
first (everything depends on it), then Broker, then Pub, then Investor, then archival, then the
end-to-end demo. The Mastodon-compatibility gateway is a late-phase addon (Phase 7).

## Phase 0 — Foundations

- Add `fxc-common` module: the instrument model of DESIGN §3.0 (sealed `Instrument` hierarchy
  with `FxSpotInstrument` and `EquityInstrument`, `AssetClass`, `SettlementProfile`; derivatives
  left as designed extension points per DESIGN §6.3), FIX 4.4 dictionary resource, config
  loader, OFX private-message-set constants (empty placeholder for now).
- Wire dependencies from the confirmed version catalog in `.reference/README.md`: QuickFIX/J
  3.0.1, GridGain 8 CE 8.9.35 (add the GridGain Nexus repo; fall back to Apache Ignite 2.18.0
  if blocked — see DESIGN §6.8), OFX4J 1.39, Smack 4.4.8, MariaDB Connector/J 3.5.9, HikariCP
  7.1.0. Add the JDK 21 `--add-opens` flags for Ignite/GridGain. (Javalin is **not** wired now —
  it belongs to the deferred Mastodon gateway, Phase 7 / DESIGN §6.2.)
- **⚠️ Tigase spike (blocking gate for FxcPub):** run **stock, unmodified** Tigase 8.4.1 as a
  docker-compose service (image `tigase/tigase-xmpp-server`) against MariaDB (load its repository
  schema via `scripts/tigase.sh install-schema`), create a pubsub node, and complete a Smack
  login + publish/subscribe round-trip **as an XMPP client**. Confirm JDK 21 and accept the
  AGPLv3 license. No custom plugins; Tigase is external, not embedded (resolved — DESIGN §6.1,
  PROBLEMS.md P1/P2).
- `docker-compose.yml` with MariaDB (`mariadb:11.8`) **and Tigase** (`tigase/tigase-xmpp-server`);
  per-component `schema.sql` stubs (Tigase's own repository schema is loaded by its schema tool).
- Per-component `conf/*.conf` with localhost defaults.
- **Exit criteria**: `./gradlew build` green with all dependencies resolving; `docker compose up`
  yields a reachable MariaDB; Tigase spike outcome recorded in PROBLEMS.md.

## Phase 1 — FxcExchange

1. Embedded GridGain node bootstrap + tables (`INSTRUMENT` with asset-class discriminator,
   `ORDERS`, `TRADE`, `SETTLEMENT_OBLIGATION`); FX pairs and equities seeded from config.
2. `MatchingEngineService`: price-time-priority book, limit + market orders, partial fills,
   per-instrument tick/lot validation; asset-class agnostic, written against `Instrument`.
   Unit-test the book exhaustively (this is the highest-value test target in the system).
3. QuickFIX/J acceptor: `NewOrderSingle` / `OrderCancelRequest` in, `ExecutionReport` out.
4. `MarketDataService`: `MarketDataRequest` subscription, snapshot + incremental refresh.
5. `ClearingService`: net fills into per-broker settlement obligations per cycle, delegating
   to each instrument's `SettlementProfile` (currency exchange for FX, DVP for equities).
- **Exit criteria**: a scripted QuickFIX/J test client can submit crossing orders in both an
  FX pair and an equity, receiving fills and market data for each.

## Phase 2 — FxcBroker

1. GridGain node + tables (`ACCOUNT`, `POSITION`, `CLIENT_ORDER`, `EXECUTION`); dev accounts
   seeded from config.
2. FIX initiator to FxcExchange; order routing and `ExecutionReport` handling in `OmsService`.
3. `AccountService`: multi-currency balances plus share positions in the unified `POSITION`
   model; simple margin check for FX, cash-up-front for equities.
4. OFX 2.x server via OFX4J: signon, account info, investment statement (equities as native
   stock holdings; FX positions as pseudo-securities per DESIGN §6.6).
5. Custom OFX order-entry message set (`FXC.ORDERMSGSRQV1`) — finalize shape here.
- **Exit criteria**: integration test drives signon → order → fill → statement shows the
  position, against a live FxcExchange, for both an FX pair and an equity.

## Phase 3 — FxcPub (XMPP-native)

Mastodon-compatibility is **not** in this phase — it is the deferred gateway addon (Phase 7).
FxcPub here is XMPP-native: stock Tigase plus FXC's XMPP-client application layer.

1. Stand up **stock, unmodified** Tigase as an external docker-compose service (per the Phase-0
   spike) with its PubSub component and repository schema on MariaDB; provision accounts (incl.
   trusted service accounts for FxcPub) from config.
2. FxcPub XMPP-client services (Smack) that publish to and subscribe from Tigase PubSub — no
   server-side Tigase code. GridGain node + hot tables (`PUB_ACCOUNT`, `STATUS`, `FOLLOW`) as
   projections fed by the pubsub events these clients receive; `TimelineService` fan-out.
   GridGain is the hot layer; Tigase+MariaDB is the durable source of XMPP truth.
3. FIX drop-copy acceptor: `ExecutionReport` → rendered status, published to the broker's feed
   via `FixGatewayService` acting as an XMPP client.
4. FxcBroker gains its XMPP bot client (Smack) and drop-copy initiator; publishes fills both ways.
- **Exit criteria**: a fill on FxcExchange appears as a status on the broker's feed, readable via
  an XMPP (Smack) subscription to the pubsub node.

## Phase 4 — FxcInvestor

1. MariaDB persistence (JDBC + `schema.sql`): config, decision log, order/position mirror.
2. OFX client: signon, statement sync, order submission via the custom message set.
3. XMPP client: home timeline ingestion + posting.
4. CLI REPL: `buy sell positions orders feed post agent on|off quit`.
5. Strategy SPI + built-in momentum/threshold demo strategy; decision loop wiring
   (market view from statements/feed → `Strategy.evaluate` → order via OFX).
- **Exit criteria**: `agent on` trades autonomously end-to-end and its fills appear on FxcPub.

## Phase 5 — Cold-data archival

- `ArchiveService` in each GridGain component: drain terminal/aged rows (orders, trades,
  settlements, statuses) from GridGain tables to the component's MariaDB schema.
- FxcPub deep-history timeline reads fall back to MariaDB.
- **Exit criteria**: hot tables stay bounded under sustained trading; archived rows queryable
  in MariaDB.

## Phase 6 — End-to-end demo & hardening

- `demo` compose/script: start MariaDB + Tigase + all four components, seed two investor accounts,
  run the agent, watch the feed over XMPP.
- Cross-component integration test in CI; README updated with the demo walkthrough.

## Phase 7 — Mastodon-compatibility gateway (late-phase addon)

Deferred per DESIGN §6.2. A **separate** service that lets stock Mastodon clients read/post
against FxcPub, without touching stock Tigase.

1. Embedded HTTP server (Javalin — wired here, not in Phase 0) exposing the Mastodon REST subset:
   `POST /api/v1/statuses`, `GET /api/v1/timelines/home|public`, `GET /api/v1/accounts/:id`,
   `POST /api/v1/accounts/:id/follow`, plus a stub OAuth token endpoint.
2. Gateway acts purely as an **XMPP client** of stock Tigase (Smack): REST writes → pubsub
   publishes; timeline reads → pubsub/GridGain projections.
3. Entity mapping: pubsub items ⇄ Mastodon `Status`/`Account` JSON (string IDs, ISO-8601 dates,
   Link-header pagination) per `.reference/mastodon-api/`.
- **Exit criteria**: a stock Mastodon client authenticates, posts a status, and sees the public
  timeline (including broker fill statuses) through the gateway.

## Suggested review checkpoints

Stop-and-review after Phases 1, 2, and 4 — those lock in the FIX usage, the OFX extension
shape, and the agent loop respectively. Review the gateway design at the start of Phase 7
(entity mapping and OAuth stub are the risk areas).
