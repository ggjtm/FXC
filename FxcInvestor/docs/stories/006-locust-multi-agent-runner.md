# Locust multi-agent load runner (replaces the Gatling harness)
Status: done
Relates to: root DESIGN §6.5 / PLAN "Additional stories" / supersedes story 005

## Summary

A Python + Locust load harness in `loadgen/` that drives continuous OFX order flow at FxcBroker, with
a **web UI to start, stop, and re-rate the workload while it is running**. Replaces the Gatling
harness of story 005. The demo now runs continuously by default.

## Motivation

The demo was a ~30-second batch, and there was no way to steer the workload from a browser. The
original request was for "a link to the Gatling UI" — but **Gatling OSS has no such UI**, verified in
the plugin bytecode rather than assumed:

- every `sim.*` knob is read in a **static initializer**, so a run is frozen when its JVM starts;
- `gatlingRun` is a fire-and-forget forked JVM (`DefaultTask`, not even `JavaExec`);
- the HTML report is a **post-run batch step** (`io.gatling.charts.report.ReportsGenerator`), which
  refuses to generate anything until the run is over;
- the plugin's only interactive pieces are `gatlingRecorder` (a Swing *script recorder*) and a stdin
  simulation picker.

A live control console exists only in **Gatling Enterprise** — commercial, API-token gated, and
nothing in this repo configures it. Locust ships that capability in the box, plus a `/swarm` REST API
and a master/worker mode for future multi-process scale-out.

## Flow

1. `scripts/demo.sh` brings up the stack and the harness container, then parks until Ctrl-C.
2. Locust begins swarming immediately (`--autostart`) at 8 users / spawn rate 2 — about 5 orders/sec.
3. Each virtual investor reads its holdings over OFX on an interval, asks its strategy for an intent,
   and POSTs an OFX order (riding a book-snapshot request in the same envelope).
4. The operator opens `http://localhost:8089` and changes users or spawn rate mid-run.

## As built

```
loadgen/
  locustfile.py             InvestorUser — pacing, account spread, per-user seed, portfolio refresh
  fxc_loadgen/ofx.py        OFX 2.x builder + response parser (no OFX4J in Python)
  fxc_loadgen/strategies.py rando / booker / bookfish ports + the shared histogram sampler
  fxc_loadgen/liquidity.py  cash-scaled sizing + liquidity floor (twin of LiquidityAwareStrategy)
  fxc_loadgen/instruments.py tick + lot table mirroring InstrumentCatalog
  tests/                    stdlib unittest — 105 tests, no pip install required
docker/locust/Dockerfile    pinned python:3.12-slim + locust 2.32.5
scripts/loadtest.sh         host-virtualenv path, for iterating on the harness
```

Java-side changes this story required:

- `agent/PortfolioCache` + `agent/PortfolioSource` — both agent loops passed `PortfolioView.empty()`,
  so no strategy could see its own holdings. Refreshed on an interval, not per tick.
- `strategy/LiquidityAwareStrategy` — the Java twin of `liquidity.py`, wired by `Strategies.byName`
  for `booker`/`bookfish` only.
- `ofx.http.threads` on the broker — its OFX pool was hardcoded to 4 and is the throughput ceiling.
- `sample_data/` — the golden OFX fixtures (first use of the convention AGENTS.md has always mandated).

## Approach notes

**The wire format is a cross-language contract, and every way of breaking it is silent.** The broker
returns **HTTP 200** for a rejected signon (signon-only envelope, `CODE 15500`) *and* for a misspelled
tag (the message set is skipped entirely). A harness trusting HTTP status would report unbroken success
while placing no orders. Three defences:

1. Golden fixtures in `../sample_data/`, generated from OFX4J by `OfxGoldenEnvelopeTest` and asserted
   byte-identical from Python by `tests/test_ofx.py`. Highest-value test in the story.
2. `require_order()` turns both silent-failure modes into loud exceptions.
3. The Locust stats table separates transport from business outcome — `POST ofx-order` fails only on
   real faults; `ORDER accepted` / `ORDER rejected:<reason>` are counters. Rejections are the system
   working, not load-test failures.

**An empty element is a fatal 400** at the broker (its parser reads it as an aggregate close and
mismatches its stack), so requests are built as strings and `_el()` *refuses* a blank value.
`xml.etree` serialises a text-less element as exactly `<TAG />`, which is why it is not used for
serialisation.

**`OfxBrokerClient.marshalOrder` was kept, not deleted.** Story 005 left it as dead code once Gatling
went, but it is now the authoritative Java-side marshaller that the golden fixtures are generated from
— one source of truth for a format two languages must agree on.

**Documented divergences from the Java agents:**

- ~~`bookfish` builds its traded-volume histogram from the XMPP feed in Java, seeing the whole market.
  Python has no XMPP client, so it accumulates only its own observations — sparser, and it behaves like
  `rando` via the fallback until a few trades are seen. Faithful in algorithm, narrower in input.~~
  **Closed by [007](007-investor-mix-control.md)**: the harness now pools observations across all virtual
  investors in the process and reads market-wide volume-by-price from the exchange's public chart feed,
  and `bookfish` *waits* rather than falling back to a uniform draw
  ([003](003-bookfish-agent.md#patience-waiting-for-an-advantage), implemented in both languages).
- Java and Python **cannot** produce the same sequence from the same seed (48-bit LCG vs Mersenne
  Twister). Reproducibility is within-language; the shared contract is the algorithm and its bounds.
- Story 005's acceptance criterion "no duplicate order-building logic" is **not** met and cannot be:
  a Python harness cannot call OFX4J. The golden fixtures are the mitigation — duplication that is
  tested against a single source of truth rather than left to drift.

**Honest regression.** Gatling resolved through Gradle at zero cost to the developer; Locust needs an
image build or a virtualenv. Containerising it follows the precedent README sets for Tigase — "a second
runtime, isolated in a container, never invoked from the host" — so the host toolchain stays JDK-21-only.

## Acceptance criteria

- [x] A browser can start, stop, and **re-rate** a run in progress (verified 8 → 20 users mid-run).
- [x] Python builds byte-identical OFX to the Java client, asserted from both sides.
- [x] All three strategies ported; `booker` prices from real relayed book depth.
- [x] Business rejections are visible but do not count as failures.
- [x] The demo runs continuously by default; `--batch` keeps a bounded run.
- [x] `./gradlew build` is unaffected — no Gradle file references `loadgen/`.
- [x] Sustained flow does not exhaust the account (see below).

## Verification

- **Python**: 105 stdlib `unittest` tests — golden-fixture equality, response parsing (accepted,
  rejected, bad signon, missing message set, book, statement), the three strategies under fixed seeds,
  tick/lot snapping, and the liquidity policy.
- **Java**: `OfxGoldenEnvelopeTest` (2), `PortfolioCacheTest` (12), `LiquidityAwareStrategyTest` (23);
  156 total, 0 failures. `RandoStrategyTest`/`HistogramSamplerTest` pass **unchanged**, which is the
  proof that `rando` stayed naive.
- **Live**: a Python-built order returned `ORDERSTATUS=ROUTED`; order+book shared one envelope; a wrong
  password was caught despite HTTP 200; book snapshots parsed with real depth.
- **Continuous run (the acceptance test)**: 3.5 minutes, **2,168 fills, 0 rejections**, both accounts
  trading (~1,000 decisions each), equity *mean-reverting* around the seeded 1,042,000 (+655 → +262)
  rather than draining. A naive one-sided flow would have exhausted 1,000 ACME or $1M and degraded into
  a reject stream.

## Out of scope / later

Gatling Enterprise. Distributed Locust master/worker across machines (no code change needed —
`--master`/`--worker` — but a single process is the default). Porting the XMPP feed to Python. A
console for FxcInvestor itself: Locust's UI now covers workload control, so DESIGN's "FxcInvestor stays
headless" decision stands.

**Followed by [007](007-investor-mix-control.md)**, which added what Gatling's `sim.mix.*` did (a
per-strategy population, steerable in the UI), the market-wide volume feed, per-strategy stats rows, and
the `sim.max*` threshold assertions this story dropped.
