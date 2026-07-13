# fxc-common — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## C1 — OFX4J package-lock constrains where custom aggregates live — **OPEN**

OFX4J's unmarshaller only resolves aggregate classes under `com.webcohesion.ofx4j.*` (root
PROBLEMS.md P3 / DESIGN §6.4). The order-entry message-set **constants** (`OfxMessageSets`) live in
`com.fxc.common.ofx`, but any **inbound** custom aggregate classes cannot. Decision deferred to
FxcBroker Phase 2: either place aggregate classes under the OFX4J namespace, or accept marshal-only
(outbound) custom aggregates. Nothing to change in common until then.

## C2 — Config format is intentionally minimal — **MITIGATED**

`FxcConfig` parses flat `key=value` files (no HOCON library) to stay dependency-free. If nested
config or interpolation is later required, the loader API (`getString`/`getInt`/`getBoolean`/
`find`) is small enough to re-back with a richer parser without touching call sites.

## C3 — Derivatives not modelled — **MITIGATED (by design)**

The sealed `Instrument` hierarchy and `AssetClass` enum are closed today (FX spot + equity only).
Adding derivatives is a deliberate extension point (DESIGN §6.3); the sealed types mean the compiler
will flag every `switch` that needs updating when they are added.
