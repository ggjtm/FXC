# FxcBroker — Component Plan

Scoped plan for the OFX brokerage + OMS. Companion to the root [docs/PLAN.md](../../docs/PLAN.md)
§Phase 2 and [docs/DESIGN.md](../../docs/DESIGN.md) §4.2.

## Status: **Phase 2 complete** — exit criteria met (28 tests green total)

Delivered:
1. [x] **GridGain node + tables** `ACCOUNT`/`POSITION`/`CLIENT_ORDER`/`EXECUTION`; dev account
   seeded from config. (`GridNode` currently duplicated from FxcExchange — see PROBLEMS.md B7.)
2. [x] **FIX initiator to FxcExchange** (`BrokerFixClient`): routes `NewOrderSingle`, handles
   `ExecutionReport`, feeds `OmsService`.
3. [x] **AccountService**: multi-currency cash + share positions in the unified `POSITION` model;
   full-funding check for FX, cash-up-front for equity buys, share-availability for equity sells.
4. [x] **OFX 2.x server**: own `com.sun.net.httpserver` handler driving OFX4J's marshaller/
   unmarshaller (`OfxCodec`); signon + investment statement (equities as `StockPosition`; home
   USD cash as `AVAILCASH`; other currency balances as `OtherPosition` FX pseudo-securities).
5. [x] **Custom OFX order-entry message set** (`FXCORDMSGSRQV1`/`RSV1`) under
   `com.webcohesion.ofx4j.domain.data.fxc` so it round-trips (validated by `OfxOrderRoundTripTest`).
6. [~] **Publication on fill** — the **FIX drop-copy** leg to FxcPub is DONE (`BrokerDropCopyClient`,
   wired via `BrokerServer`; forwards fills to FxcPub which publishes them to the broker's feed —
   see FxcPub Phase 3). The **XMPP bot** leg (broker posts directly as its own account) is still open.
7. [ ] **ArchiveService** (root Phase 5) — drain terminal orders/executions to `fxc_broker` MariaDB.

**Exit criteria met**: `BrokerIntegrationTest` drives signon → order → fill → statement (position
shown) against a live FxcExchange with a BROKER2 liquidity client, for both EUR/USD and ACME.

## Backlog / next

- [x] **Investor-requested order-book snapshot** ([stories/001](stories/001-order-book-snapshot.md)):
  DONE — the broker subscribes to exchange market data (FIX), caches per-symbol depth
  (`md/MarketDataCache`), and answers investor OFX book requests (`FXCMDMSGSRQV1`/`RSV1`, shared in
  fxc-common). Unblocks FxcInvestor `booker`. Verified by `BookRelayIntegrationTest`.
- [ ] Publication legs (FIX drop-copy + XMPP bot) once FxcPub is unblocked (PROBLEMS.md B4).
- [ ] `ArchiveService` to MariaDB cold schema (root Phase 5).
- [ ] Consolidate `GridNode` into a shared `fxc-grid` module (PROBLEMS.md B7).
- [ ] Broker-side tick/lot pre-validation (currently relies on the exchange to reject).
- [ ] Market order cash checks (skipped up front — price unknown).

## Dependencies / sequencing

- Requires a running **FxcExchange** (Phase 1, done) as the FIX target.
- The XMPP publication leg (item 6b) depends on **FxcPub/Tigase**, which is on hold (AGPLv3). The FIX
  drop-copy leg (6a) does not — it targets FxcPub's FIX acceptor and can be built/tested with a stub.
