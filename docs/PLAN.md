# FXC Implementation Plan

Status: **Phases 0–6 complete, plus the DESIGN §6 component consoles; only the Phase 7
Mastodon-gateway addon remains (deferred).** All four components run together end to end: an
autonomous FxcInvestor agent trades over OFX, the fill routes Broker → Exchange, is drop-copied to
FxcPub, published to the broker's XMPP feed, and read back by the investor — proven by
`scripts/demo.sh` and the `EndToEndDemoIT` orchestrator. Phase 4 delivered the full Strategy SPI +
all three agents (`rando`, `booker`, `bookfish`), OFX client, the single-instance runner, XMPP feed ingestion, MariaDB decision log, and the interactive **CLI REPL**. Phase 5 added
shared `ColdStore` archival across all three GridGain components with FxcPub deep-history fallback.
The post-roadmap stories below added the FxcExchange and FxcBroker demo consoles (DESIGN §6) with the
backend they needed — trading-session state, mass cancel, per-account P&L — and then replaced the
Gatling harness with a Python + Locust one whose UI can re-rate a run in progress, making the demo
continuous — and then made the *investor population* steerable from that UI as well, one of each
strategy by default, with a `bookfish` that waits for an advantage rather than guessing.
**180 Java tests + 203 Python tests**, 0 failures (integration tests skip gracefully without
MariaDB/Tigase; the Python suite needs no pip install, and the 19 tests that do need locust skip
themselves). Companion to [DESIGN.md](DESIGN.md).

Phases are ordered so every phase ends with something runnable and testable. Exchange comes
first (everything depends on it), then Broker, then Pub, then Investor, then archival, then the
end-to-end demo. The Mastodon-compatibility gateway is a late-phase addon (Phase 7).

## Phase 0 — Foundations — DONE

- Add `fxc-common` module: the instrument model of DESIGN §3.0 (sealed `Instrument` hierarchy
  with `FxSpotInstrument` and `EquityInstrument`, `AssetClass`, `SettlementProfile`; derivatives
  left as designed extension points per DESIGN §7.3), FIX 4.4 dictionary resource, config
  loader, OFX private-message-set constants (empty placeholder for now).
- Wire dependencies from the confirmed version catalog in `.reference/README.md`: QuickFIX/J
  3.0.1, GridGain 8 Ultimate Edition 8.9.35 (add the GridGain Nexus repo; the node needs a signed
  `gridgain-license.xml`, referenced from the root `gridgain.properties` — see PROBLEMS.md P5),
  OFX4J 1.39, Smack 4.4.8, MariaDB Connector/J 3.5.9, HikariCP 7.1.0. Add the JDK 21 `--add-opens`
  flags for GridGain. (Javalin is **not** wired now —
  it belongs to the deferred Mastodon gateway, Phase 7 / DESIGN §7.2.)
- **⚠️ Tigase spike (blocking gate for FxcPub):** run **stock, unmodified** Tigase 8.4.1 as a
  docker-compose service (image `tigase/tigase-xmpp-server`) against MariaDB (load its repository
  schema via `scripts/tigase.sh install-schema`), create a pubsub node, and complete a Smack
  login + publish/subscribe round-trip **as an XMPP client**. Confirm JDK 21 and accept the
  AGPLv3 license. No custom plugins; Tigase is external, not embedded (resolved — DESIGN §7.1,
  PROBLEMS.md P1/P2).
- `docker-compose.yml` with MariaDB (`mariadb:11.8`) **and Tigase** (`tigase/tigase-xmpp-server`);
  per-component `schema.sql` stubs (Tigase's own repository schema is loaded by its schema tool).
- Per-component `conf/*.conf` with localhost defaults.
- **Exit criteria**: `./gradlew build` green with all dependencies resolving; `docker compose up`
  yields a reachable MariaDB; Tigase spike outcome recorded in PROBLEMS.md.

## Phase 1 — FxcExchange — DONE

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

## Phase 2 — FxcBroker — DONE

1. GridGain node + tables (`ACCOUNT`, `POSITION`, `CLIENT_ORDER`, `EXECUTION`); dev accounts
   seeded from config.
2. FIX initiator to FxcExchange; order routing and `ExecutionReport` handling in `OmsService`.
3. `AccountService`: multi-currency balances plus share positions in the unified `POSITION`
   model; simple margin check for FX, cash-up-front for equities.
4. OFX 2.x server via OFX4J: signon, account info, investment statement (equities as native
   stock holdings; FX positions as pseudo-securities per DESIGN §7.6).
5. Custom OFX order-entry message set (`FXC.ORDERMSGSRQV1`) — finalize shape here.
- **Exit criteria**: integration test drives signon → order → fill → statement shows the
  position, against a live FxcExchange, for both an FX pair and an equity.

## Phase 3 — FxcPub (XMPP-native) — DONE

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

## Phase 4 — FxcInvestor — DONE

1. [x] MariaDB persistence (JDBC + `schema.sql`): config, decision log, order/position mirror.
2. [x] OFX client: signon, statement sync, order submission via the custom message set.
3. [x] XMPP client: home timeline ingestion + posting (Smack `FeedClient`).
4. [x] CLI REPL: `buy sell positions orders feed post agent on|off quit`.
5. [x] Strategy SPI + built-in agents (`rando`/`booker`/`bookfish`); decision loop wiring
   (market view from statements/feed → `Strategy.decide` → order via OFX). The `booker` agent is
   fed by the FxcBroker order-book relay (FxcBroker/docs/stories/001).
6. [x] Opt-in multi-agent load runner for performance / bulk simulation. Originally Gatling
   (FxcInvestor/docs/stories/005); **replaced** by the Python + Locust harness in `loadgen/`
   (docs/stories/006) because Gatling OSS cannot start, stop, or re-rate a run in progress.
- **Exit criteria (met)**: `agent on` trades autonomously end-to-end and its fills appear on FxcPub.
  See FxcBroker/docs/PROBLEMS.md B9 for the signon credential-default fix found while validating
  the REPL order→fill flow.

## Phase 5 — Cold-data archival — DONE

- [x] Shared `com.fxc.common.store.ColdStore` (HikariCP + JDBC, schema apply) in fxc-common.
- [x] `ArchiveService` in each GridGain component: drain terminal/aged rows to the component's
  MariaDB schema, MariaDB-first then delete-by-id (idempotent, no data loss on failure):
  - FxcExchange: terminal orders + all trades + settlement obligations.
  - FxcBroker: terminal client orders + their executions.
  - FxcPub: statuses aged past the hot retention window.
- [x] Each component schedules archival (`archive.intervalMs`) and wires a best-effort `ColdStore`
  in `Main`; a scheduler-off variant (`intervalMs <= 0`) supports manual test passes.
- [x] FxcPub deep-history timeline reads fall back to MariaDB (`TimelineService` unions the hot
  `STATUS` projection with the cold `STATUS_ARCHIVE`, newest-first, deduped).
- **Exit criteria (met)**: hot tables stay bounded under sustained trading; archived rows queryable
  in MariaDB. Verified by `ExchangeArchiveIntegrationTest`, `BrokerArchiveIntegrationTest`,
  `PubArchiveIntegrationTest` (all gated on the MariaDB container).

## Phase 6 — End-to-end demo & hardening — DONE

- [x] `demo` script (`scripts/demo.sh`): brings up MariaDB + Tigase, starts all three backend
  components in dependency order, seeds **two** investor accounts (cash + ACME shares — FxcBroker
  `Main` now seeds `account.dev`/`account.dev2`), runs two autonomous `rando` agents whose orders
  cross to produce fills, and streams the fills back off the FxcPub XMPP feed. Leaves infra up on
  exit (`--down` to tear down); backend logs in `build/demo-logs/`.
- [x] Cross-component integration orchestrator: `com.fxc.investor.EndToEndDemoIT` boots
  FxcExchange + FxcBroker (with drop-copy) + FxcPub against live Tigase, drives a `rando` agent over
  OFX, and asserts the full chain — order **fills**, the fill is **published** to the broker's feed,
  and the investor **reads it back over XMPP** and folds it into its market view. Skips (does not
  fail) when Tigase is unreachable.
- [x] README updated with the demo walkthrough and a Tests section documenting which integration
  tests need MariaDB / Tigase and how they skip.
- **Exit criteria (met)**: the four components run together end to end; a fill originating from an
  autonomous agent surfaces on the investor's own feed. Verified by `EndToEndDemoIT` (18s against
  live Tigase).

## Phase 7 — Mastodon-compatibility gateway (late-phase addon)

Deferred per DESIGN §7.2. A **separate** service that lets stock Mastodon clients read/post
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

## Additional stories (post-roadmap)

Feature stories layered on the completed components, tracked in each component's `docs/stories/`.

- **FxcExchange — Exchange feeds (`docs/stories/001`) — DONE.** Market-surveillance price data:
  FIX raw quotes in three depth tiers + last sale (`MarketDepth`), a framework-free REST candle
  service (OHLCV + volume-by-price, age-based granularity floors, hot+cold trade reads), a
  hand-rolled RFC 6455 live-ticker WebSocket, and a self-contained charting web UI at
  `http://localhost:8090/`. New package `com.fxc.exchange.feed`; a `ts` column was added to `TRADE`
  / `TRADE_ARCHIVE`. Verified by `CandleAggregatorTest`, `WebSocketFeedServerTest`,
  `FeedHttpServerIntegrationTest`, `FeedUiServingTest`, `MarketDataDepthTest`.

- **fxc-common — Shared web toolkit (`docs/stories/001`) — DONE.** Everything the component consoles
  have in common, in `com.fxc.common.web` and served off the classpath: `Json` (promoted from
  `com.fxc.exchange.feed`), `HttpJson` (responses, CORS/preflight, method **and exact-path** gating),
  `StaticAssets` (classpath serving with a MIME map, traversal allowlist and `ETag`/304), plus
  `web/common/` — the dark theme, the hover menu, the D3 status indicator, the fetch/poll/socket
  helpers, and a **vendored** `d3.v7.min.js`. No new Gradle dependencies. Verified by
  `StaticAssetsTest`, `JsonTest`.

- **FxcExchange — Controller/monitor console (`docs/stories/002`) — DONE.** Root DESIGN §6.2. Adds the
  exchange's first market-state primitive (`TradingSession`: market-wide + per-symbol halt, combined
  to the safer state), mass cancel (`OrderBook.cancelAll` / `MatchingEngine.clearBook`) that reports
  every cancellation to its owning broker over FIX via `CancelReporter`, and
  `ExchangeControlService` behind `GET /api/status|book` + `POST /api/session/halt|open`,
  `/api/book/clear` (query-parameter POSTs — still no JSON parser). The chart is rewritten in D3+SVG,
  which fixed sparse candle buckets rendering with compressed time gaps; the live ticker gained a
  heartbeat and a real `pong`. Controls are gated by `feed.controls.enabled`. Verified by
  `TradingSessionTest`, `MatchingEngineHaltTest`, `ExchangeControlApiTest`, `WebSocketFeedServerTest`,
  `FeedUiServingTest`.

- **FxcBroker — Monitor/controller console (`docs/stories/002`) — DONE.** Root DESIGN §6.3. Adds the
  repo's first P&L (`PnlService` + `FxRates`: session-relative mark-to-market equity in USD, sampled
  per fill, `realized + unrealized == relative`), an operator start/stop-trading gate on `OmsService`,
  the accessors the console needs (`AccountService.accounts()`, `MarketDataCache.lastPrices()`,
  `BrokerFixClient.isLoggedOn()`), and `account_number` + `ts` on `EXECUTION`/`EXECUTION_ARCHIVE`
  (cold schema v2) — without which a fill could not be attributed to an account at all. Served by a
  **separate** HTTP server on 8083 so the OFX endpoint stays POST-only. Verified by `FxRatesTest`,
  `PnlServiceTest`, `BrokerWebApiTest`.

  **Front-end caveat for all three:** the console JavaScript is covered by served-asset assertions, a
  replayed-payload geometry check and a structural scan, but has **never been executed** — no browser
  or JS engine was available in the build environment. Open both consoles and look at them before
  demoing.

- **FxcInvestor — Locust load harness (`docs/stories/006`) — DONE.** Root DESIGN §6.5. Replaced the
  Gatling harness (story 005, now removed) because Gatling OSS freezes every `sim.*` knob in a static
  initializer and cannot start, stop, or re-rate a run in progress — verified in the plugin bytecode.
  New standalone `loadgen/` (Python + Locust, **not** a Gradle module, so `./gradlew build` is
  unaffected), containerised per the Tigase precedent, with a live control UI on `:8089`. Speaks OFX 2.x
  without OFX4J, guarded by **golden fixtures** in `FxcInvestor/sample_data/` that are generated from
  OFX4J and asserted byte-identical from both languages — necessary because every way of getting the
  wire format wrong returns HTTP 200. Brought two Java changes with it: `agent/PortfolioCache` (both
  agent loops had been passing `PortfolioView.empty()`, so no strategy could see its own holdings) and
  `strategy/LiquidityAwareStrategy`, which scales `booker`/`bookfish` buying to available cash and sells
  to maintain a cash floor — the thing that lets a continuous run avoid exhausting an account. Also
  `ofx.http.threads` on the broker, whose OFX pool was a hardcoded 4 and is the throughput ceiling.
  `scripts/demo.sh` is now continuous by default (`--batch` for the bounded run); `scripts/loadtest.sh`
  is the host-virtualenv path. Verified by `OfxGoldenEnvelopeTest`, `PortfolioCacheTest`,
  `LiquidityAwareStrategyTest`, 105 Python `unittest` tests, and a continuous run of **2,168 fills with
  zero rejections** and equity mean-reverting rather than draining.

- **FxcInvestor — Investor mix control + a patient `bookfish` (`docs/stories/007`) — DONE.** Root DESIGN
  §2/§6.5. All three strategies now run at once — **one of each by default, in the demo too** — with the
  number of each steerable in the Locust UI mid-run as shares of the population; business outcomes are
  reported per strategy, and Gatling's dropped `sim.max*` assertions come back as `--max-p95-ms` /
  `--max-error-pct` / `--min-accepted` gating the process exit code. `bookfish` gained the market-wide
  traded-volume signal Python never had (the exchange's public volume-by-price feed plus a process-wide
  shared `MarketView`) and, with it, **patience**: it declines to trade until it can name an advantage
  against volume-weighted fair value instead of silently taking `rando`'s uniform fallback —
  implemented twice, as `strategy.PatientStrategy` and `fxc_loadgen.patience`, per the rule that a
  strategy must not mean two different things in two languages. Per-class user counts were the obvious
  mechanism for the mix and cannot be changed mid-run (PROBLEMS.md P16); the strategy is an attribute of
  an investor instead. Verified by `PatientStrategyTest` (24) plus 98 new Python tests, with
  `RandoStrategyTest`/`HistogramSamplerTest`/`LiquidityAwareStrategyTest` unchanged, and by live
  re-mixing a running demo.

## Suggested review checkpoints

Stop-and-review after Phases 1, 2, and 4 — those lock in the FIX usage, the OFX extension
shape, and the agent loop respectively. Review the gateway design at the start of Phase 7
(entity mapping and OAuth stub are the risk areas).
