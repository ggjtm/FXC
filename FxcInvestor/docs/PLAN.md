# FxcInvestor — Component Plan

Scoped plan for the agent + CLI client. Companion to the root [docs/PLAN.md](../../docs/PLAN.md)
§Phase 4 and [docs/DESIGN.md](../../docs/DESIGN.md) §4.4.

**Note:** FxcInvestor uses **MariaDB as its primary store** (not GridGain) and does not embed a
GridGain node — so no Ignite `--add-opens` flags either.

## Status: **complete** (root Phase 4; Phases 0–3 complete). Post-roadmap stories 006/007 are done
too — see [stories/](stories/).

## Plan (root Phase 4)

1. [x] **MariaDB persistence** (plain JDBC + HikariCP): `InvestorStore` applies `db/schema.sql` and
   persists the **decision log** (`DECISION_LOG`) — every tick's decision (submitted orders and
   skips). Wired best-effort into the runner (runs without the DB if unreachable). `AGENT_CONFIG`/
   `ORDER_MIRROR`/`POSITION_MIRROR` remain a later increment.
2. [x] **OFX client** (OFX4J): signon to FxcBroker, statement sync, order submission via the custom
   `FXCORDMSGSRQV1` message set (now shared in `fxc-common` so broker + investor round-trip it).
3. [x] **XMPP client** (Smack): `FeedClient` — feed ingestion folds fill statuses into `MarketView`
   (last-sale + traded-volume, driving `bookfish`) and posts statuses; wired into the runner
   best-effort. (CLI `feed`/`post` verbs pending with item 4.)
4. [x] **CLI REPL**: `buy sell positions orders feed post agent on|off quit` —
   `com.fxc.investor.cli.Repl`, reached with `-Dmode=repl`. (This item was left unchecked long after
   the root PLAN recorded Phase 4 as delivered; the code has all the verbs.)
5. [x] **Strategy SPI** + agents + decision loop. `rando`, `booker`, and `bookfish` all implemented
   over a shared `PriceTargetSampler`/`SamplingStrategy` shell; single-instance runner built.

## Stories

Agent strategies and runners are specified as stories in [stories/](stories/):

- [001 — `rando`](stories/001-rando-agent.md): uniform-random side/qty; price target ±1% of last sale. **(implemented)**
- [002 — `booker`](stories/002-booker-agent.md): price target from a quantity-weighted **order-book** histogram, ≤1σ from last sale. **(implemented)**
- [003 — `bookfish`](stories/003-bookfish-agent.md): price target from a **traded-volume** histogram, ≤0.5σ from last sale, and **patient** — it declines to trade until it can name an advantage against volume-weighted fair value (`strategy.PatientStrategy`, twinned in Python). **(implemented)**
- [004 — single-instance runner](stories/004-single-instance-runner.md): run one agent (OFX + live XMPP feed) with a selectable strategy. **(implemented — feed wired, CLI REPL in `cli/Repl`)**
- [005 — Gatling multi-agent runner](stories/005-gatling-multi-agent-runner.md): **superseded by 006 and removed.** Gatling could not start, stop, or re-rate a run in progress.
- [006 — Locust multi-agent runner](stories/006-locust-multi-agent-runner.md): Python + Locust load harness in `loadgen/` with a live control UI on :8089. **(implemented — `scripts/demo.sh` or `scripts/loadtest.sh`)** Brought with it `PortfolioCache` (both agent loops previously passed `PortfolioView.empty()`) and `LiquidityAwareStrategy`, which makes `booker`/`bookfish` sustainable indefinitely.

**Note (booker data source):** `booker`'s order-book histogram needs live book depth, which the
plain OFX/XMPP investor doesn't see — it falls back to `rando` behavior until fed a book snapshot
(FxcBroker/docs/stories/001). `bookfish` is fully driven by the XMPP feed it already consumes; the
Locust harness, which has no XMPP client, substitutes the exchange's public volume-by-price feed
(story 007) and abstains until one of the two has given it something to go on.

All three agents share a pluggable `PriceTargetSampler` seam over a common agent shell; only the
price-target distribution and σ filter differ.

## Exit criteria (root Phase 4)

`agent on` trades autonomously end-to-end and its fills appear on FxcPub.

## Review checkpoint

Root PLAN stops for review after Phase 4 — it locks in the agent loop.

## Dependencies / sequencing

- Requires **FxcBroker** (Phase 2) for OFX signon/statements/order entry.
- The timeline/feed features (item 3, and the `feed`/`post` CLI verbs) depend on **FxcPub/Tigase**,
  which is no longer on hold (root PROBLEMS.md P2, accepted 2026-07-13): stock Tigase 8.4.1 runs in
  its own container and `scripts/demo.sh` starts it. The OFX-driven trading path (statements + orders
  + strategy) remains independent of it.
