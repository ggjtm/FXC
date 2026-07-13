# FxcExchange — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## E1 — Embedded GridGain on JDK 21 — **RESOLVED**

Root PROBLEMS.md P4. `GridNodeTest` boots a real embedded node with the `--add-opens` flags from the
root build; DDL applies and SQL round-trips (~1.25 s). No `--illegal-access=permit` used.

## E2 — Services are node-hosted POJOs, not Ignite Service deployments — **OPEN (decision pending)**

DESIGN §3.1 implementation note. On a single node this is functionally equivalent to formal
`org.apache.ignite.services.Service` deployment and keeps live wiring (FIX sessions, listeners)
simple. Awaiting Jeremy's call on whether to adopt the formal Service Grid API now or defer to
multi-node scale-out.

## E3 — FIX market-data required-group validation — **RESOLVED**

Empty-book `MarketDataSnapshotFullRefresh` initially omitted the required `NoMDEntries(268)` count
and was rejected by FIX44 validation on the wire. Fixed by emitting `268=0` for empty books and
skipping truly empty incrementals.

## E4 — Price/quantity precision over FIX — **OPEN (low)**

FIX 4.4 price/qty fields are `double`; the domain uses `BigDecimal`. Conversion happens at the FIX
boundary. Acceptable for matching, but settlement reconciliation may want string-preserved decimals
or a scaling convention. Revisit if discrepancies appear.

## E5 — No self-trade prevention — **OPEN (low)**

A broker can cross its own resting order. Fine for the demo; add STP if realism requires it.

## E6 — Fixed FIX sessions — **OPEN (low)**

Acceptor is configured with fixed `BROKER1`/`BROKER2` sessions. Dynamic acceptor sessions are needed
for arbitrary brokers (demo/hardening, root Phase 6).
