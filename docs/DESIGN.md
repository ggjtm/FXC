# FXC System Design

Status: **implemented through Phase 5** (all four components + cold-data archival are built and
tested; see [PLAN.md](PLAN.md) for phase-by-phase status). This document remains the architectural
reference — the settled decisions and data model below match the code; the Mastodon gateway (§6.2)
and derivatives (§6.3) are the notable not-yet-built pieces.

## 1. Overview

FXC is four independent components exchanging messages over standard financial and social
protocols. The instrument universe covers **FX spot pairs** (EUR/USD, GBP/USD, USD/JPY, ...)
and **cash equities** (ticker symbols), unified behind a common instrument abstraction (§3.0).
Derivatives (options, futures) are an explicit ToDo (§6.3).

```
   Mastodon clients ┄┄┄▶[ XMPP↔Mastodon gateway — late-phase addon (§6.2) ]
                                     ┊ (deferred)
                         ┌──────────────────────────┐
   XMPP clients ────────▶│         FxcPub           │
                         │  stock Tigase XMPP core  │
                         │ + XMPP-client app layer  │
                         │  (GridGain 8 hot state)  │
                         └──────▲──────────▲────────┘
                                │XMPP      │FIX 4.4 drop-copy
                                │(bot user)│(ExecutionReports)
 ┌─────────────┐   OFX 2.x   ┌──┴──────────┴─────────┐   FIX 4.4    ┌───────────────────────┐
 │ FxcInvestor │────HTTP────▶│       FxcBroker       │─────────────▶│     FxcExchange       │
 │ agent + CLI │             │  OFX server + OMS     │◀─────────────│ market data, matching │
 │  (MariaDB)  │◀───XMPP────▶│ (GridGain 8 hot state)│  orders/MD/  │       clearing        │
 └─────────────┘   to Pub    └───────────────────────┘  fills       │ (GridGain 8 hot state)│
                                                                    └───────────────────────┘
                All components archive cold/historical data to MariaDB.
```

## 2. Settled decisions

| Decision | Choice |
|---|---|
| FxcPub architecture | Stock (100% unmodified) Tigase XMPP server + FxcPub XMPP-client app layer; Mastodon REST deferred to a late-phase gateway addon (§6.2). Was Vysper — see [PROBLEMS.md](PROBLEMS.md) P1 |
| Broker → Pub channel | Both: FIX drop-copy session AND XMPP client (bot account) |
| FIX engine / version | QuickFIX/J, FIX 4.4 |
| FxcInvestor UI | Headless agent + thin CLI (REPL) |
| Asset classes | FX spot pairs + cash equities, behind a common instrument abstraction; derivatives deferred (ToDo) |
| OFX stack | OFX 2.x XML via OFX4J |
| Agent brain | Rule-based pluggable `Strategy` interface |
| Hot state (Pub/Broker/Exchange) | GridGain 8 services + GridGain 8 tables, in-memory by default |
| FxcInvestor persistence | MariaDB |
| Cold / archival data (all components) | MariaDB |

## 3. Data architecture

### 3.0 Instrument model (asset-class abstraction)

All trading components share one instrument model (living in `fxc-common`) so the matching
engine, OMS, market data, and clearing are written once against the abstraction rather than
per asset class:

```java
sealed interface Instrument permits FxSpotInstrument, EquityInstrument /* ToDo: OptionInstrument, FutureInstrument */ {
    String symbol();            // exchange symbol: "EUR/USD", "ACME"
    AssetClass assetClass();    // FX_SPOT, EQUITY  (ToDo: OPTION, FUTURE)
    Currency quoteCurrency();   // currency prices are expressed in
    BigDecimal tickSize();      // minimum price increment
    BigDecimal lotSize();       // minimum quantity increment
    SettlementProfile settlement();  // how fills become obligations (see below)
}
```

- **`FxSpotInstrument`** — base/quote currency pair; a fill moves two currency balances
  (buy EUR/USD = +EUR, −USD). Settlement profile: bilateral currency exchange, T+2 convention.
- **`EquityInstrument`** — ticker + issuer name + settlement currency; a fill moves a share
  position against a cash balance. Settlement profile: delivery-versus-payment cash settlement,
  T+1 convention. No corporate actions (dividends, splits) in scope initially.
- **`SettlementProfile`** is the strategy object `ClearingService` uses to turn a `Trade` into
  `SETTLEMENT_OBLIGATION` rows, so clearing stays asset-agnostic.
- **Positions** are modeled uniformly as `(account, instrument | currency, quantity)`:
  currency balances and share positions share the `POSITION` table shape, discriminated by a
  holding type column.
- The matching engine, order model, and FIX mapping operate on `Instrument` only; nothing in
  the order path branches on asset class. Asset-class-specific behavior is confined to
  `SettlementProfile` and the OFX statement mapping (§4.2).
- **Derivatives (options, futures) are explicitly out of scope for now** — the sealed
  hierarchy, `AssetClass` enum, and `SettlementProfile` are the designated extension points;
  see §6.3.

### 3.1 GridGain 8 (hot state) — FxcPub, FxcBroker, FxcExchange

- Each component embeds its **own single-node GridGain 8 cluster** (components are independent;
  no shared cluster). Cluster size is configuration, so any component can scale out later.
- Domain logic is deployed as **GridGain services** (`org.apache.ignite.services.Service`),
  giving each component cluster-singleton or node-singleton semantics per service.
  - **Implementation note (Phase 1):** on the single-node embedded topology the FXC services are
    currently **node-hosted POJOs that use the GridGain data grid for all state** (SQL tables /
    caches), rather than formal `Service` deployments. This keeps live wiring (FIX sessions,
    listeners) simple and sidesteps service-serialization subtleties; wrapping them as Service
    Grid deployments is mechanical and deferred until multi-node scale-out is actually needed.
- Operational data lives in **GridGain SQL tables** (caches with query entities / `CREATE TABLE`),
  **in-memory by default**; GridGain native persistence stays off unless configured.
- Dependency: GridGain 8 Community Edition artifacts (`org.gridgain:ignite-core` et al. from the
  GridGain Maven repository). Apache Ignite 2.x is the API-compatible fallback if artifact access
  is a problem.

### 3.2 MariaDB (durable + cold)

- **FxcInvestor** uses MariaDB as its primary store (agent config, decision log, order/position
  history as reported over OFX).
- **All four components** archive historical data to MariaDB: filled/cancelled orders, trades,
  settlement records, published statuses, market data snapshots. Each component owns a schema
  (`fxc_pub`, `fxc_broker`, `fxc_exchange`, `fxc_investor`) on a shared dev server.
- Archival is an async background service in each component (in the GridGain components, a
  GridGain service) that drains closed/aged records from hot tables to MariaDB.
- Dev environment: `docker-compose.yml` at the repo root provisioning one MariaDB instance;
  schemas created by per-component `schema.sql` applied on startup.

## 4. Components

### 4.1 FxcExchange — market data, matching, clearing

- **FIX acceptor** (QuickFIX/J): one session per broker. Inbound `NewOrderSingle(D)`,
  `OrderCancelRequest(F)`, `MarketDataRequest(V)`; outbound `ExecutionReport(8)`,
  `MarketDataSnapshotFullRefresh(W)`, `MarketDataIncrementalRefresh(X)`.
- **GridGain services**:
  - `MatchingEngineService` — price-time-priority limit order book per instrument (asset-class
    agnostic; operates on `Instrument` from §3.0); market and limit orders; partial fills;
    per-instrument tick/lot validation.
  - `MarketDataService` — publishes top-of-book and trades to subscribed FIX sessions.
  - `ClearingService` — nets fills into settlement obligations per broker per cycle by
    delegating to each instrument's `SettlementProfile` (currency exchange for FX, DVP cash
    settlement for equities); writes settlement records.
  - `ArchiveService` — drains terminal orders/trades/settlements to MariaDB.
- **GridGain tables**: `INSTRUMENT` (with asset-class discriminator), `ORDERS`, `TRADE`,
  `SETTLEMENT_OBLIGATION`.
- Instruments seeded from configuration (initial set: EUR/USD, GBP/USD, USD/JPY, AUD/USD spot
  pairs plus a handful of fictional equities, e.g. ACME, GLOBEX, INITECH).

### 4.2 FxcBroker — OFX brokerage + OMS

- **OFX 2.x server** (OFX4J over an embedded HTTP server): signon, account info, and investment
  statement download (positions, transactions, balances) for FxcInvestor clients. Equity
  positions map natively to OFX stock holdings; FX positions are reported as pseudo-securities
  (§6.6).
  - OFX has **no native order-entry messages**; order placement uses a **custom private
    message set** (`<FXC.ORDERMSGSRQV1>`) carried in the same OFX envelope. Flagged as an open
    item in §6.4.
- **OMS** (`OmsService`, GridGain service): validates client orders (account exists, tick/lot
  compliance, margin/balance check), routes to FxcExchange over a QuickFIX/J **initiator**
  session, tracks order state from `ExecutionReport`s, updates positions. Asset-class agnostic —
  operates on `Instrument` (§3.0).
- **AccountService**: cash balances per currency plus share positions, unified in the
  `POSITION` model of §3.0; simple margin rule for FX, cash-up-front for equities.
- **Publication**: on every fill, (a) sends drop-copy `ExecutionReport` over a second QuickFIX/J
  initiator session to FxcPub, and (b) posts a human-readable status via XMPP (Smack client) as
  its bot account on FxcPub.
- **GridGain tables**: `ACCOUNT`, `POSITION`, `CLIENT_ORDER`, `EXECUTION`.
- `ArchiveService` drains terminal orders/executions to MariaDB.

### 4.3 FxcPub — stock Tigase + XMPP-client application layer

Tigase was chosen over Vysper after the reference research (see [PROBLEMS.md](PROBLEMS.md) P1/P2):
actively maintained, scale-oriented, and shipping a substantially more complete XEP-0060 PubSub
component. FxcPub separates cleanly into an **XMPP server** (stock Tigase) and an **application
layer** (FXC's own code), which are joined *only* through standard XMPP.

**Design principle — Tigase runs 100% unmodified to avoid triggering AGPLv3 constraints.** The
FXC customization boundary is explicit and has exactly two sides:

1. **Server side — configuration only.** Tigase runs as the **unmodified** vendor distribution
   (docker-compose service, image `tigase/tigase-xmpp-server`). FXC adds **no** custom Tigase
   plugins, components, processors, or patched builds — the only server-side inputs are supported
   `config.tdsl` and `dataSource` settings (virtual host, components enabled, JDBC repository).
   Because the binary is unchanged and run as a separate process, AGPLv3's copyleft (which attaches
   to *modified* or *conveyed* versions of the covered work) is not triggered against FXC code.
2. **Client side — custom features via standard XMPP.** All FXC-specific behavior lives in the
   application layer as **standard XMPP clients** (Smack) talking to Tigase over the wire. This is
   where custom features go (feed projections, FIX-gateway rendering, timeline fan-out); none of it
   links against or derives from Tigase's AGPLv3 source.

Rationale:

- **AGPLv3 avoidance** — an unmodified server run as a separate network service, spoken to only via
  standard XMPP, keeps Tigase's network copyleft off FXC's own code. (Legal confirmation still
  advised, but no source is modified or distributed.) See PROBLEMS.md P2.
- **Upgrade safety & simplicity** — we can track Tigase releases without reconciling a fork.
- **Portability** — because we only depend on standard XMPP + XEP-0060, Tigase could later be
  swapped for another compliant server with no application changes.

Components of FxcPub:

- **Tigase XMPP server** (v8.4.1, stock, port 5222) with its PubSub (XEP-0060) component; each
  account has a feed node, follows are subscriptions. Tigase persists its **own** XMPP state
  (users, auth, offline messages, pubsub nodes/items) via its native JDBC repository → **MariaDB**.
  External service, not embedded — no supported embed-as-library API (PROBLEMS.md P2).
- **FxcPub XMPP-client services** — FXC application code that connects to Tigase as ordinary XMPP
  client(s) via Smack (trusted service accounts). These subscribe to pubsub feeds, publish items,
  and maintain read-models. No server-side code runs inside Tigase.
- **GridGain services (hot application state)**: `TimelineService` (fan-out projections for
  home/public feeds), `AccountDirectoryService`, `FixGatewayService`, `ArchiveService` — fed by
  the pubsub events the XMPP client services receive.
- **GridGain tables (hot)**: `PUB_ACCOUNT`, `STATUS`, `FOLLOW` — in-memory projections/caches,
  consistent with FxcBroker/FxcExchange. The durable source of XMPP truth remains Tigase+MariaDB.
- **FIX drop-copy acceptor** (QuickFIX/J): receives `ExecutionReport`s from brokers and renders
  them as statuses (e.g. "FILLED: BUY 100,000 EUR/USD @ 1.0842"), which `FixGatewayService`
  publishes to the broker's feed **as an XMPP client**.
- **MariaDB (durable + cold)**: hosts Tigase's repository schema *and* the FXC cold archive —
  statuses older than a configured horizon are archived from GridGain to MariaDB; deep-history
  reads fall back to MariaDB.

**Deferred — Mastodon-compatibility gateway (late-phase addon).** Exposing a Mastodon-compatible
REST API (`/api/v1/...`) so stock Mastodon clients can read/post is **not** part of the initial
FxcPub. It is designed and built later as a **separate XMPP↔Mastodon gateway addon** (§6.2) that
also acts purely as an XMPP client of stock Tigase — keeping the unmodified-Tigase principle
intact. Until then, FxcPub is XMPP-native and its clients (FxcBroker, FxcInvestor) speak XMPP
directly. The Javalin dependency and the REST/OAuth surface move to that addon's phase.

### 4.4 FxcInvestor — agent + CLI

- **Headless agent** with a thin interactive CLI (REPL over stdin: `buy`, `sell`, `positions`,
  `orders`, `feed`, `post`, `agent on|off`, `quit`).
- **OFX client** (OFX4J): signon to FxcBroker, statement download, order submission via the
  custom message set.
- **XMPP client** (Smack): connects to FxcPub to read the home timeline (input signal) and post
  the agent's own commentary.
- **Strategy SPI**: `interface Strategy { Decision evaluate(MarketView, PortfolioView, FeedView); }`
  with one built-in momentum/threshold demo strategy. Deterministic and unit-testable.
- **MariaDB persistence** (plain JDBC + `schema.sql`): agent config, decision log, mirrored
  order/position history. Same store doubles as its archive.

## 5. Shared infrastructure

- **`fxc-common` Gradle module** (small, deliberate exception to "independent"): the
  instrument model of §3.0 (`Instrument` hierarchy, `AssetClass`, `SettlementProfile`),
  FIX 4.4 data-dictionary XML, OFX private-message-set constants, config loading helpers.
  No business logic. Components stay independently runnable.
- **Ports** (dev defaults): Tigase XMPP 5222, FxcPub FIX drop-copy acceptor 9878;
  FxcBroker OFX HTTP 8082; FxcExchange FIX acceptor 9876; MariaDB 3306. (Mastodon-gateway
  REST port 8081 is reserved for the late-phase addon, §6.2.)
- **Config**: each component reads an HOCON/properties file (`conf/<component>.conf`) with
  sensible localhost defaults so `./gradlew :X:run` works out of the box.

## 6. Open items (flagged, not blocking)

Confirmed dependency coordinates and versions live in `.reference/README.md` (gathered
2026-07-13). Items marked ⚠️ were escalated by that research and warrant a decision.

1. **⚠️ Tigase adoption (FxcPub XMPP core), run unmodified.** Vysper was dropped as unviable on
   Java 21 and replaced by stock Tigase 8.4.1 (full write-up in [PROBLEMS.md](PROBLEMS.md) P1/P2).
   Settled (Phase 0/3 complete): Tigase runs **100% unmodified** as an **external service** (no
   embed-as-library API, no custom plugins), and FxcPub interacts with it purely as a **standard
   XMPP client** (§4.3) via a trusted service-account Smack client. AGPLv3 is accepted.
   **Resolved JDK finding:** Tigase 8.4.1 runs on **JDK 17, not 21/25** — its bundled Groovy/ASM
   cannot read Java 21+ class files (`Unsupported class file major version 65`). Our image builds
   `FROM eclipse-temurin:17-jre`; because Tigase is a separate container this does not constrain
   FXC's own Java-21 processes (see README "JDK requirements", PROBLEMS.md P5).
2. **ToDo: Mastodon-compatibility gateway (late-phase addon).** Exposing a Mastodon-compatible
   REST API (`/api/v1/statuses`, `/timelines/home`, `/timelines/public`, `/accounts/:id`,
   `/follows`, stub OAuth) so stock Mastodon clients can read/post is **deferred**, out of the
   initial FxcPub. It will be a **separate XMPP↔Mastodon gateway** that also acts purely as an
   XMPP client of stock Tigase (preserving the unmodified-Tigase principle), translating REST
   calls to XMPP/PubSub and rendering pubsub items back as Mastodon `Status`/`Account` entities.
   The Javalin dependency and the REST/OAuth surface belong to this phase, not the core. Design
   details (entity mapping, OAuth stub, pagination) to be worked out then; see
   `.reference/mastodon-api/`. Sequenced as a late phase in [PLAN.md](PLAN.md).
3. **ToDo: derivatives (options, futures)** — explicitly out of scope for now. Extension
   points are designed in: add `OptionInstrument`/`FutureInstrument` to the sealed `Instrument`
   hierarchy, new `AssetClass` values, and new `SettlementProfile` implementations (margining,
   expiry/exercise, mark-to-market). The matching engine and OMS should need no changes; OFX
   statement mapping and margin rules will.
4. **OFX order entry** — the private message-set extension (`<FXC.ORDERMSGSRQV1>`) is
   non-standard by necessity; shape to be finalized during FxcBroker implementation. **Note:**
   OFX4J's unmarshaller only resolves aggregate classes under `com.webcohesion.ofx4j.*` (its
   classpath scan is package-locked), so inbound custom aggregates must live in that package
   namespace — otherwise the message set is marshal-only. See `.reference/ofx/ofx4j-usage.md`.
5. **⚠️ OFX4J server side is thin** — the whole server contract is one method
   (`OFXServer.getResponse(RequestEnvelope)`); FxcBroker hand-builds every response aggregate
   (`SONRS`, `INVSTMTRS`, `SECLIST`, `STATUS`). Its `OFXServlet` is a `javax`/Jakarta servlet;
   on Java 21 we bypass it and call `AggregateMarshaller`/`AggregateUnmarshaller` from our own
   HTTP handler. More broker-side work than "use the library" implies.
6. **FX positions in OFX** — equities map natively to `POSSTOCK`/`STOCKINFO` (CUSIP); FX pairs
   map to `POSOTHER`/`OtherPosition` with a synthetic `SECID` (e.g. `UNIQUEID=FX:EURUSD`).
7. **Auth realism** — OFX signon (and the deferred Mastodon OAuth) use static dev credentials
   initially.
8. **GridGain 8 artifact access** — GridGain CE is **not on Maven Central**; the build must add
   the GridGain Nexus repo, or use the Apache Ignite 2.x fallback (identical package namespace,
   so code is unchanged). Embedded Ignite/GridGain on JDK 21 also needs a specific `--add-opens`
   flag set — captured in `.reference/gridgain/README.md`.
9. **Javalin version (gateway phase only)** — needed by the deferred Mastodon gateway (§6.2), not
   the core. Current is **7.2.2** (Java 17+), whose routing API moved into a `config.routes { }`
   block; pin Javalin 6.x if we prefer the classic `app.get(...)` API.
