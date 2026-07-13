# Multi-agent Gatling runner (performance & bulk simulation)

Status: proposed
Relates to: [004](004-single-instance-runner.md); root PLAN Phase 6 (demo/hardening)

## Summary

A **Gatling**-based harness that spins up **many** investor agents concurrently to drive load
against the broker/exchange for **performance testing** and **bulk market simulation**. Where the
single-instance runner ([004](004-single-instance-runner.md)) is one agent for demos, this is N
agents for throughput, latency, and market-dynamics experiments.

## Approach

- **Gatling** (Scala/Java DSL) as the load-model + reporting engine: it models a population of
  virtual users (agents), an injection profile (ramp/steady/spike), and produces latency/throughput
  reports.
- Each virtual user runs the agent decision logic (`rando`/`booker`/`bookfish`) against the broker's
  **OFX** endpoint. Because OFX rides over HTTP, Gatling's HTTP DSL POSTs the marshalled OFX order
  envelopes directly (reusing the shared OFX codec + custom order-entry aggregates in `fxc-common`).
- A configurable **population mix** (e.g. 80% `rando`, 15% `booker`, 5% `bookfish`) and per-agent
  seed produces reproducible-yet-varied order flow.
- Market-data for `booker`/`bookfish` is fed to the harness (FxcExchange FIX market-data
  subscription or a shared snapshot service) so book/volume-weighted samplers have live input.

## Scope / mechanics

- New Gradle module or source set (`FxcInvestor` `gatlingSimulation`) with the Gatling plugin;
  **not** wired into the default `build`/`test` (perf runs are opt-in via a dedicated task, e.g.
  `./gradlew :FxcInvestor:gatlingRun`).
- Scenarios: `warmup`, `steady-load` (fixed rate), `ramp` (0→N users), `spike`. Parameterized by
  users, duration, symbol set, population mix, think-time.
- Assertions: p95/p99 OFX round-trip latency and error-rate thresholds (perf gates); plus optional
  market-outcome captures (fills/sec, price path) for simulation analysis.

## Acceptance criteria

- `gatlingRun` launches a configurable population of agents against a running broker/exchange and
  emits a Gatling report (latency percentiles, throughput, errors).
- Population mix, injection profile, duration, and seed are configurable.
- Reuses the production strategy code and the shared OFX codec (no duplicate order-building logic).

## Notes

- Dependency choice (Gatling version, Java vs Scala DSL) and whether it lives in a separate module
  to keep it out of the core dependency graph are open decisions for implementation time.
- This is a **late/optional** deliverable (aligns with root Phase 6 hardening); the three agents
  and the single-instance runner come first.
