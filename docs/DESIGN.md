# FXC System Design

Status: **implemented through Phase 6, plus the §6 live demo UI** (all four components, cold-data
archival, the end-to-end demo, the FxcExchange/FxcBroker consoles, and the Locust load harness are
built and tested; the demo runs continuously — see [PLAN.md](PLAN.md) for phase-by-phase status). This document remains the architectural
reference — the settled decisions and data model below match the code; the Mastodon gateway (§7.2)
and derivatives (§7.3) are the notable not-yet-built pieces.

## 1. Overview

FXC is four independent components exchanging messages over standard financial and social
protocols. The instrument universe covers **FX spot pairs** (EUR/USD, GBP/USD, USD/JPY, ...)
and **cash equities** (ticker symbols), unified behind a common instrument abstraction (§3.0).
Derivatives (options, futures) are an explicit ToDo (§7.3).

```
   Mastodon clients ┄┄┄▶[ XMPP↔Mastodon gateway — late-phase addon (§7.2) ]
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
| FxcPub architecture | Stock (100% unmodified) Tigase XMPP server + FxcPub XMPP-client app layer; Mastodon REST deferred to a late-phase gateway addon (§7.2). Was Vysper — see [PROBLEMS.md](PROBLEMS.md) P1 |
| Broker → Pub channel | Both: FIX drop-copy session AND XMPP client (bot account) |
| FIX engine / version | QuickFIX/J, FIX 4.4 |
| FxcInvestor UI | Headless agent + thin CLI (REPL) |
| Asset classes | FX spot pairs + cash equities, behind a common instrument abstraction; derivatives deferred (ToDo) |
| OFX stack | OFX 2.x XML via OFX4J |
| Agent brain | Rule-based pluggable `Strategy` interface |
| Hot state (Pub/Broker/Exchange) | GridGain 8 services + GridGain 8 tables, in-memory by default |
| GridGain edition & license | Ultimate Edition; its signed XML license is located by one property in the committed root `gridgain.properties`, which the build resolves into `-Dgridgain.license.url` for every `run`/`test` JVM (§3.1) |
| FxcInvestor persistence | MariaDB |
| Cold / archival data (all components) | MariaDB |
| Component consoles (§6) | FxcExchange + FxcBroker only; static HTML+CSS+JS over REST, dark theme, D3+SVG. FxcPub stays headless; FxcInvestor's workload is steered from the Locust UI instead (§6.5) |
| D3 delivery | Full `d3.v7.min.js` **vendored** into `fxc-common` resources and served off the classpath — the demo runs offline, and a static asset keeps the framework-free rule intact (§6.1) |
| Console controls | Unauthenticated; each component gates them behind one config key and can serve a read-only console (§6.4, §7.8) |
| Investor load harness | **Locust** (Python, `loadgen/`, containerised) — replaced Gatling, whose knobs freeze at JVM start so a run cannot be steered. Outside the Gradle build; UI on :8089 (§6.5) |
| Investor liquidity policy | The non-naive strategies (`booker`, `bookfish`) scale buying to available cash and sell assets to maintain a cash floor, so a continuous demo does not exhaust an account. `rando` stays naive (§6.5) |

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
  see §7.3.

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
- Dependency: GridGain 8 **Ultimate Edition** artifacts (`org.gridgain:gridgain-ultimate` et al.
  from the GridGain Nexus repository — see [BUILDING.md](BUILDING.md#gridgain-license)). The former
  API-compatible Apache Ignite 2.x drop-in fallback has been removed.
- **License resolution.** Ultimate is a licensed edition: every node must be handed a signed
  **GridGain 8 XML** license (v2.1 — *not* the GridGain 9 JSON form) at start, via
  `GridGainConfiguration.setLicenseUrl(...)`. The design names the license *location* in exactly one
  committed place and keeps the license *file* itself out of version control:
  - **`gridgain.properties`** (repo root, committed) declares the location in a single property,
    `gridgain.license.file` — either a path resolved relative to the repo root, or a value that
    already carries a URL scheme (`file:`, `http:`, …), which is used verbatim. Relocating the
    license is a one-line edit here and nowhere else.
  - **The root `build.gradle.kts`** reads that property once, resolves it to a canonical absolute
    `file:///` URL, and injects it as the `gridgain.license.url` system property into every
    Gradle-launched component JVM — `run` (`JavaExec`) and `test` (`Test`) alike, for all three
    GridGain components. The URL must be absolute because forked JVMs run with the subproject
    directory as their working directory, not the repo root.
  - **`GridNode.licenseUrl()`** (one per GridGain component) resolves the location at node start
    with the precedence `-Dgridgain.license.url` → `GRIDGAIN_LICENSE_URL` env var → the bare
    filename `gridgain-license.xml` relative to the launch directory. The last is the fallback for
    non-Gradle launches (packaged distributions); bare paths are converted to absolute `file://`
    URLs, since `setLicenseUrl` takes a URL rather than a path.
  - The license file itself is **gitignored** and obtained per the GridGain contract. Compiling and
    assembling need no license; *starting a node* does — which includes most `./gradlew test` runs,
    as a missing or invalid license fails node start with `ProductLicenseException`. Operator-facing
    steps are in [BUILDING.md](BUILDING.md#gridgain-license).

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
- **GridGain tables**: `INSTRUMENT` (with asset-class discriminator), `ORDERS`, `TRADE` (carries a
  `ts` execution-time column for the feed service), `SETTLEMENT_OBLIGATION`.
- Instruments seeded from configuration (initial set: EUR/USD, GBP/USD, USD/JPY, AUD/USD spot
  pairs plus a handful of fictional equities, e.g. ACME, GLOBEX, INITECH).
- **Feed service (`com.fxc.exchange.feed`, FxcExchange/docs/stories/001)** — market-surveillance
  price data over three channels: (a) **FIX** raw quotes to brokers in three depth tiers
  (top-of-book / 5-level / full) plus last sale, via `MarketDepth(264)` on the market-data request;
  (b) a framework-free **REST** service (JDK `HttpServer`) serving time-bucketed OHLCV **candles** +
  a volume-by-price histogram, with age-based minimum-granularity floors and hot+cold trade reads;
  and (c) a live one-second **ticker WebSocket** (hand-rolled RFC 6455 — no web framework, matching
  the OFX transport) feeding a **charting web UI**. No new runtime dependencies.
- **Trading session & console (`com.fxc.exchange.control`, docs/stories/002)** — `TradingSession`
  (market-wide + per-symbol halt, combined to the safer state), `MatchingEngine.clearBook` mass
  cancel with FIX `CANCELED` reporting via `CancelReporter`, and `ExchangeControlService` behind the
  console's status/control REST endpoints (§6.2).

### 4.2 FxcBroker — OFX brokerage + OMS

- **OFX 2.x server** (OFX4J over an embedded HTTP server): signon, account info, and investment
  statement download (positions, transactions, balances) for FxcInvestor clients. Equity
  positions map natively to OFX stock holdings; FX positions are reported as pseudo-securities
  (§7.6).
  - OFX has **no native order-entry messages**; order placement uses a **custom private
    message set** (`<FXC.ORDERMSGSRQV1>`) carried in the same OFX envelope. Flagged as an open
    item in §7.4.
- **OMS** (`OmsService`, GridGain service): validates client orders (account exists, tick/lot
  compliance, margin/balance check), routes to FxcExchange over a QuickFIX/J **initiator**
  session, tracks order state from `ExecutionReport`s, updates positions. Asset-class agnostic —
  operates on `Instrument` (§3.0).
- **AccountService**: cash balances per currency plus share positions, unified in the
  `POSITION` model of §3.0; simple margin rule for FX, cash-up-front for equities.
- **Publication**: on every fill, (a) sends drop-copy `ExecutionReport` over a second QuickFIX/J
  initiator session to FxcPub, and (b) posts a human-readable status via XMPP (Smack client) as
  its bot account on FxcPub.
- **GridGain tables**: `ACCOUNT`, `POSITION`, `CLIENT_ORDER`, `EXECUTION` (the last carries
  `account_number` + `ts` for the console's P&L series — see §6.3).
- `ArchiveService` drains terminal orders/executions to MariaDB.
- **Console (`com.fxc.broker.web`, `com.fxc.broker.pnl`, docs/stories/002)** — an operator
  start/stop-trading gate on `OmsService`, session mark-to-market P&L per account (`PnlService`,
  `FxRates`), and a second HTTP server on 8083 serving the monitor console (§6.3).

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
FxcPub. It is designed and built later as a **separate XMPP↔Mastodon gateway addon** (§7.2) that
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
- **Holdings** are read over OFX on an interval by `PortfolioCache` and passed to the strategy. Both
  agent loops previously passed `PortfolioView.empty()`, so no strategy could see its own cash or
  shares; the liquidity policy (§6.5) depends on this. An interval rather than per tick because a
  statement read is a round trip and the broker's OFX pool is small (`ofx.http.threads`).
- **Load generation is a separate concern** — see §6.5. The Java agents are the architectural
  participants (Strategy SPI, XMPP timeline, decision log); volume comes from the Locust harness.

## 5. Shared infrastructure

- **`fxc-common` Gradle module** (small, deliberate exception to "independent"): the
  instrument model of §3.0 (`Instrument` hierarchy, `AssetClass`, `SettlementProfile`),
  FIX 4.4 data-dictionary XML, OFX private-message-set constants, config loading helpers.
  No business logic. Components stay independently runnable.
- **Ports** (dev defaults): Tigase XMPP 5222, FxcPub FIX drop-copy acceptor 9878;
  FxcBroker OFX HTTP 8082 and **console HTTP 8083**; FxcExchange FIX acceptor 9876, **feed/console
  HTTP 8090 and live-ticker WebSocket 8091**; **Locust load-harness UI 8089** (§6.5); MariaDB 3306.
  GridGain discovery: exchange 47500, broker 47510, pub 47520. (Mastodon-gateway REST port 8081 is
  reserved for the late-phase addon, §7.2.)
  - **Trap:** `FxcExchange/conf/fxcexchange.conf`'s `fix.acceptor.*` keys are *not* read — the FIX
    port comes from the classpath `quickfixj/exchange-acceptor.cfg`. Editing the conf does not move
    it.
- **Config**: each component reads an HOCON/properties file (`conf/<component>.conf`) with
  sensible localhost defaults so `./gradlew :X:run` works out of the box.

## 6. Live Demo UI

Status: **implemented** for FxcExchange and FxcBroker (stories `FxcExchange/docs/stories/002`,
`FxcBroker/docs/stories/002`, `fxc-common/docs/stories/001`). Consoles at
`http://localhost:8090/` (exchange) and `http://localhost:8083/` (broker).

Requirements:

1. Each component described in this section provides a controller/monitor UI.
2. The UI is a static HTML+CSS+JS web interface interacting with a REST API.
3. The UI adopts a "dark" theme and provides a D3-based status indicator as the main pane.
4. The UI provides a mouseover dropdown menu of controls.

**Scope — FxcExchange and FxcBroker only.** FxcPub and FxcInvestor stay headless (§4.3/§4.4); the
shared toolkit below is built so adding a console for either is additive. Jeremy's call, 2026-07-28.

### 6.1 Shared web toolkit (`fxc-common`)

The two consoles are one product, so their common parts live in `fxc-common` under
`com.fxc.common.web` and are served off the classpath from the `fxc-common` jar — no per-component
copies and no build step:

- **`Json`** — the hand-rolled JSON writer, promoted from `com.fxc.exchange.feed`. Write-only by
  design: control endpoints take **query parameters with an empty body**, so FXC still needs no JSON
  *parser* (§4.1's framework-free rule).
- **`HttpJson`** — response/CORS/preflight helpers, method gating, and `requireExactPath`. The last
  exists because `HttpServer` contexts match by longest prefix: a context at `/api/book` also
  receives `/api/book/clear`, and without an exact-path check an endpoint either serves a path it
  does not own or reports "method not allowed" for a path it never had.
- **`StaticAssets`** — classpath static serving with a MIME map, a path-traversal allowlist, and a
  content-hash `ETag` (so the ~280 KB D3 bundle is read and hashed once, then answered 304). Resolves
  before gating the method, so a POST to an unserved path reads as 404 rather than 405.
- **Shared assets** under `web/common/`: `fxc.css` (the theme), `fxc-menu.js` (the §6.4 dropdown),
  `fxc-status.js` (the §6.3 D3 status indicator), `fxc-api.js` (polling with backoff, WebSocket
  reconnect + staleness), and `vendor/d3.v7.min.js`.

**D3 is vendored, not fetched** (`web/common/vendor/`, with provenance in that directory's
README). The demo runs offline, so a CDN reference is not acceptable; the full minified bundle is
used rather than a module subset precisely so no npm/node build step enters the repo. It is a static
*asset*, not a Gradle runtime dependency — `gradle/libs.versions.toml` is untouched and the
framework-free convention holds.

**Colour is validated, not eyeballed.** The categorical series slots, the candle up/down pair and the
status steps were each measured against the console surface (`#16181d`) for lightness band, chroma
floor, colour-vision-deficiency separation and contrast. Two results shaped the design: candle
up/down clears the CVD target only narrowly, so candles carry **shape** as well as colour (hollow
body = up, filled = down, the traditional convention); and the status steps are a reserved set held
to contrast plus a **glyph and text label**, never colour alone.

### 6.2 FxcExchange console

Dropdown controls start and stop trading sessions (market-wide or for the selected symbol) and clear
the order book. The main pane connects to the feed service and displays 1-minute price candles for a
selectable traded security, with a transparent volume bar chart underlay in the bottom 20% of the
chart canvas, plus story 001's right-side volume-by-price histogram at 30% opacity.

Backend added for it (§4.1): `TradingSession` (the exchange had **no** market-state concept — the
matching engine accepted orders unconditionally and FIX sessions run 24h), `OrderBook.cancelAll` /
`MatchingEngine.clearBook`, and `ExchangeControlService` behind
`GET /api/status`, `GET /api/book`, `POST /api/session/halt|open`, `POST /api/book/clear`.

**Clearing the book reports every cancellation back over FIX** (`CancelReporter`, implemented by
`ExchangeApplication`). Each broker's OMS tracks order state from `ExecutionReport`s, so orders that
vanished from the exchange without a `CANCELED` report would leave every connected broker believing
it still had live orders — a silent divergence, and the single most important behaviour in this
section.

The live ticker also gained a **heartbeat** and a real **pong** reply: a quiet market publishes no
tick, so without them a client cannot tell silence from a dead socket. A D3 `scaleTime` x axis
replaced the previous position-indexed Canvas renderer, which drew the sparse candle series (empty
buckets are omitted) with compressed, untruthful time gaps.

### 6.3 FxcBroker console

Dropdown controls start and stop trading. The main pane displays a ticker of last sale prices from
the exchange feed at the top, and a line plot of each managed account's trade count on the X axis
against relative account profit and loss on the Y axis over a trading session.

Backend added for it (§4.2): an `OmsService` operator gate (independent of the exchange's market
state — the broker can stand down while the exchange stays open), `PnlService` + `FxRates`, and
`BrokerConsoleService` behind `GET /api/status|accounts|lastsale|pnl` and
`POST /api/trading/start|stop` on a **separate HTTP server** (port 8083) so the OFX endpoint stays
POST-only and the console can be disabled independently. The broker console polls its own REST
endpoints once a second rather than opening a WebSocket, so it keeps working when the exchange feed
is off and reads the broker's own market data instead of reaching past it.

**P&L definition.** Equity is valued in USD as cash plus share positions marked at the exchange's
last sale; `relative` is equity now minus equity at session start. `realized` is closed-trade P&L
(an equity sell against its VWAP cost basis) and `unrealized` is the remainder, so the two always
sum to `relative`. An FX spot fill contributes nothing to `realized` — it swaps one balance for
another — and shows up through revaluation instead. Points are sampled **as fills happen** because
the broker does not historise marks, so the curve cannot be reconstructed afterwards; a restart
therefore starts a new session. Stated approximations: a share with no mark yet is valued at cost
basis (valuing it at zero would invent a large gain on its first trade), a holding whose currency has
no resolvable USD rate is excluded and *counted* (`unpricedHoldings`) rather than guessed at, and the
baseline is taken once and never revised.

`EXECUTION` (and `EXECUTION_ARCHIVE`) gained `account_number` and `ts`: a fill could otherwise only
be attributed to an account by joining `CLIENT_ORDER`, which the archiver deletes, so a session's
P&L history was not derivable from stored data at all.

### 6.4 Control-endpoint exposure

The control endpoints mutate live trading and are **unauthenticated**, consistent with §7.7 ("auth
realism"). Each component therefore gates them behind one config key — `feed.controls.enabled`
(exchange) and `web.controls.enabled` (broker) — which, when false, does not register the mutating
contexts at all and makes the console render read-only. They also bind whatever host the component
binds, which for the dev defaults is `0.0.0.0`; a non-demo deployment wants both a bind address and
real authentication. Tracked as §7.8.


### 6.5 Investor workload: the Locust load harness and the liquidity policy

Status: **implemented** (FxcInvestor/docs/stories/006). Harness UI at `http://localhost:8089`;
`scripts/demo.sh` runs continuously by default.

**Why Locust replaced Gatling.** The demo needed a workload that runs until stopped and can be steered
from a browser. Gatling OSS cannot do the second part, and this was verified in the plugin bytecode
rather than assumed: every `sim.*` knob is read in a *static initializer* and frozen when the JVM
starts, `gatlingRun` is a fire-and-forget forked task, and the HTML report is a post-run batch step
that refuses to emit anything until the run is over. A live control console exists only in the
commercial **Gatling Enterprise**. Locust ships one, plus a `/swarm` REST API and a master/worker mode
that leaves multi-process scale-out open without code changes.

**Where it lives.** `loadgen/` — a standalone Python package, **not** a Gradle module, so
`./gradlew build` is byte-for-byte unaffected. This is the same reasoning that kept npm out of the repo
for D3 (§6.1): a second language is acceptable as an isolated *tool*, not as a second build step. It is
containerised (`docker/locust/`), following the precedent set for Tigase — a second runtime, isolated
in a container, never invoked from the host — so the host toolchain stays JDK-21-only. The honest cost:
Gatling installed itself through Gradle for free, and this does not (see BUILDING.md).

**The wire contract is the risk, because every way of breaking it is silent.** The harness speaks OFX
2.x to FxcBroker without OFX4J, and the broker answers **HTTP 200** for a rejected signon (signon-only
envelope, `CODE 15500`) *and* for a misspelled tag (the message set is skipped entirely). A harness
trusting HTTP status would report unbroken success while placing no orders. Three defences:

- **Golden fixtures** in `FxcInvestor/sample_data/`, generated from OFX4J by `OfxGoldenEnvelopeTest`
  and asserted byte-identical from Python. `OfxBrokerClient.marshalOrder` is retained as the
  authoritative Java-side marshaller for exactly this purpose.
- **Loud failures**: the Python client turns both silent modes into exceptions rather than returning a
  half-parsed answer.
- **Split metrics**: the harness reports transport separately from business outcome, so a broker
  rejection — the system working — never colours the failure ratio but is always visible by reason.

Two broker-side facts the harness exposed: an **empty element is a fatal 400** (the parser reads it as
an aggregate close), so requests are built as strings and a blank value is refused; and the OFX server
was a hardcoded four-thread pool, now `ofx.http.threads`, which is the real ceiling on throughput.

**Liquidity policy.** The seeded accounts hold 1,000 shares and $1,000,000; a one-sided stream exhausts
one of them within minutes and then produces only rejections — which is why the demo could not run
continuously. The **non-naive** strategies (`booker`, `bookfish`) are therefore wrapped in a policy
that (1) forces a SELL sized to restore a cash floor when cash falls below a fraction of the cash first
observed, (2) caps buys to a fraction of the cash *above* that floor, so buying cannot breach it, and
(3) clamps sells to holdings rather than being rejected for shorting. `rando` stays **naive** by design
(story 001) and is wrapped by nothing.

Implemented twice — Java `strategy.LiquidityAwareStrategy` and Python `fxc_loadgen.liquidity` — with
matching tests, because `booker` must not mean two different things in two languages. A **decorator**
rather than a change to `SamplingStrategy`, so `rando`'s existing tests pass untouched and prove it.
Requirements round **up** onto the lot grid (a floor must actually be reached) while bounds round
**down** (never exceed cash or holdings). Both fail closed: with no holdings data they decline to trade.

Verified by a continuous run: **2,168 fills over 3.5 minutes with zero rejections**, equity
mean-reverting around the seeded baseline rather than draining.

## 7. Open items (flagged, not blocking)

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
   FXC's own Java-21 processes (see README "JDK requirements", PROBLEMS.md P2).
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
8. **⚠️ Console controls are unauthenticated and bind `0.0.0.0`.** The §6 consoles ship with
   operator controls that mutate live trading (halt/resume, clear the order book, stop/start broker
   order handling) behind no authentication, on the dev bind address. Mitigated for demo use by a
   per-component switch (`feed.controls.enabled`, `web.controls.enabled`) that unregisters the
   mutating endpoints entirely and renders a read-only console; consistent with item 7 above, which
   this shares a root cause with. Anything beyond a demo needs real auth and a bind address, and the
   destructive actions want an audit trail. The consoles' read paths and visualizations are
   **implemented** (§6) — this item is the security posture only.
9. **Javalin version (gateway phase only)** — needed by the deferred Mastodon gateway (§7.2), not
   the core. Current is **7.2.2** (Java 17+), whose routing API moved into a `config.routes { }`
   block; pin Javalin 6.x if we prefer the classic `app.get(...)` API.
