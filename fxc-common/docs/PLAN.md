# fxc-common — Component Plan

Scoped plan for the shared library. Companion to the root [docs/PLAN.md](../../docs/PLAN.md) and
[docs/DESIGN.md](../../docs/DESIGN.md). `fxc-common` is the deliberate exception to "independent
components": small, no business logic, shared by the trading components.

## Status: **Phase 0 delivered**

Done (root PLAN Phase 0):
- Instrument model (DESIGN §3.0): sealed `Instrument` + `FxSpotInstrument`, `EquityInstrument`,
  `AssetClass`, `SettlementProfile` (data record; FX currency-exchange T+2, equity DVP T+1 defaults).
- `FxcConfig` — dependency-free layered config loader (system props → file → default).
- `OfxMessageSets` — placeholder constants for the custom OFX order-entry message set.
- `InstrumentCatalog` — the shared instrument universe (promoted from FxcExchange in Phase 2 so the
  exchange and broker agree on symbols/tick/lot/settlement); `defaults()`, `bySymbol()`, `find()`.
- FIX 4.4 dictionary resource note (`src/main/resources/fix/README.md`) — uses QuickFIX/J's bundled
  `FIX44.xml` unless a custom dictionary is needed.
- 5 unit tests green.

## Backlog / next

- [ ] **Derivatives extension points** (DESIGN §6.3): add `OptionInstrument`/`FutureInstrument`,
  new `AssetClass` values, and `SettlementProfile` styles (margining, expiry/exercise, MTM).
  Explicitly out of scope until the trading components need them.
- [ ] **OFX order-entry aggregates** (DESIGN §6.4): the inbound custom aggregates must live under
  `com.webcohesion.ofx4j.*` (OFX4J unmarshaller is package-locked). Decide during FxcBroker (Phase 2)
  whether the shared constants stay here and the aggregate classes live in FxcBroker.
- [ ] **Config format**: revisit if flat `key=value` proves limiting (HOCON was the original idea);
  the loader API is designed to allow a drop-in swap.
- [ ] **Shared FIX/OFX helpers**: promote any duplication that emerges across Exchange/Broker.

## Non-goals

- No business logic (matching, OMS, clearing, timelines) — those live in the components.
- No GridGain / MariaDB / QuickFIX-J / Smack dependencies leaking into common beyond what the
  shared model strictly needs.
