# FxcInvestor — Component Plan

Scoped plan for the agent + CLI client. Companion to the root [docs/PLAN.md](../../docs/PLAN.md)
§Phase 4 and [docs/DESIGN.md](../../docs/DESIGN.md) §4.4.

**Note:** FxcInvestor uses **MariaDB as its primary store** (not GridGain) and does not embed a
GridGain node — so no Ignite `--add-opens` flags either.

## Status: **not started** (root Phase 4; after FxcBroker)

## Plan (root Phase 4)

1. [ ] **MariaDB persistence** (plain JDBC + `schema.sql`): agent config, decision log,
   order/position mirror. Tables defined here (`AGENT_CONFIG`, `DECISION_LOG`, `ORDER_MIRROR`,
   `POSITION_MIRROR`). Doubles as the archive.
2. [ ] **OFX client** (OFX4J): signon to FxcBroker, statement sync, order submission via the custom
   `FXC.ORDERMSGSRQV1` message set (shape finalized in FxcBroker Phase 2).
3. [ ] **XMPP client** (Smack): home-timeline ingestion (input signal) + posting the agent's own
   commentary to FxcPub. **Depends on FxcPub/Tigase** (currently on hold).
4. [ ] **CLI REPL**: `buy sell positions orders feed post agent on|off quit`.
5. [ ] **Strategy SPI** + built-in momentum/threshold demo strategy; decision loop wiring
   (market view from statements/feed → `Strategy.evaluate` → order via OFX). Deterministic and
   unit-testable.

## Exit criteria (root Phase 4)

`agent on` trades autonomously end-to-end and its fills appear on FxcPub.

## Review checkpoint

Root PLAN stops for review after Phase 4 — it locks in the agent loop.

## Dependencies / sequencing

- Requires **FxcBroker** (Phase 2) for OFX signon/statements/order entry.
- The timeline/feed features (items 3, and the `feed`/`post` CLI verbs) depend on **FxcPub/Tigase**
  (on hold). The OFX-driven trading path (statements + orders + strategy) is independent and can be
  built and tested against FxcBroker first; wire XMPP once Tigase is unblocked.
