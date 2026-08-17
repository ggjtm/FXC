# FXC

A multi-module Gradle project of four independent system components plus a shared library. The
GridGain-backed components (Exchange, Broker, Pub) hold hot state in an embedded in-memory
GridGain 8 Ultimate Edition node and archive cold/terminal data to MariaDB; FxcInvestor is a
MariaDB-backed agent and REPL client. Running a GridGain component requires a license — see
[GridGain license](#gridgain-license).

## Modules

- **FxcExchange** — minimal market data, trade matching, and clearing. Embedded GridGain node,
  FIX 4.4 acceptor.
- **FxcBroker** — a minimal OFX brokerage with an order management system (OMS). Connects to
  FxcExchange via FIX, accepts OFX from FxcInvestor instances, and drop-copies fills to FxcPub.
  Embedded GridGain node.
- **FxcPub** — the XMPP-native publication component. Runs as a trusted Smack client against a
  **stock, unmodified Tigase** XMPP server (a separate container); embedded GridGain node for the
  timeline projection.
- **FxcInvestor** — an autonomous agent and REPL client for FxcBroker/FxcPub (MariaDB-backed).
- **loadgen** — a Python + Locust load harness with a browser UI to steer the workload live
  (`loadgen/`, not a Gradle module). Replaced the Gatling harness, which could not re-rate a run
  in progress.
- **fxc-common** — shared library (instrument catalog, OFX codec + custom aggregates, config,
  the `ColdStore` MariaDB helper).

## JDK requirements

Different parts of the system are pinned to different JDKs. Getting these wrong produces
non-obvious failures, so they are called out per component below.

| Component / process        | JDK        | Where it runs        | Why this exact JDK |
|----------------------------|------------|----------------------|--------------------|
| **Gradle build launcher**  | **21**     | your shell / CI      | Building on JDK 25 crashes the Kotlin DSL parser (`IllegalArgumentException: 25.0.3` in `JavaVersion.parse`). Point `JAVA_HOME` at a JDK 21 before running `./gradlew`. |
| FxcExchange, FxcBroker, FxcPub, FxcInvestor (app + tests) | **21** | JVM (Gradle toolchain) | All modules compile and run on Java 21 via the Gradle toolchain (`languageVersion = 21`). |
| **Embedded GridGain 8** (inside Exchange, Broker, Pub) | **21**, but **requires `--add-opens` JVM flags** | same JVM as the owning component | GridGain/Ignite on JDK 9+ needs a fixed set of `--add-opens` flags to reach `jdk.internal.*` and `sun.*`. Without them the node fails to start. The flags are defined once in the root `build.gradle.kts` as `igniteJvmArgs`, applied to each `application` run (`applicationDefaultJvmArgs`) and to every `Test` task. Standalone launches must set them too (the bundled `ignite.sh` does). |
| **Locust load harness** | n/a (Python 3.12) | its own Docker container (`fxc-locust`), or `loadgen/.venv` | No JVM. Containerised like Tigase so the host toolchain stays JDK-21-only; `scripts/loadtest.sh` sets up the virtualenv alternative for iterating on the harness. |
| **Tigase XMPP server** | **17** (NOT 21/25) | its own Docker container | Tigase 8.4.1 bundles a Groovy whose ASM cannot read Java 21+ class files (`Unsupported class file major version 65`); its supported JDK is 17. The image is built `FROM eclipse-temurin:17-jre`. Because it is a separate container, this is independent of FXC's own Java-21 processes (see `FxcBroker/docs/PROBLEMS.md` P5). |
| MariaDB 11.8 | n/a (native) | its own Docker container | No JVM. |

### Notes

- **One JDK 21 for everything you build/run locally.** The only exception is Tigase, which is
  containerized and carries its own JDK 17 — you never invoke it from the host JVM.
- If your default `java` is newer than 21 (e.g. 24/25), set `JAVA_HOME` for the Gradle launcher:
  ```sh
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
  ```
  Or pin it durably in `gradle.properties` with `org.gradle.java.home=/path/to/jdk-21`.

## GridGain license

The embedded engine is **GridGain 8 Ultimate Edition**, a licensed edition — Exchange, Broker, and
Pub each load a signed license when their node starts. This is needed to **run** (including
`./gradlew test`, since most GridGain tests boot a node), but not to compile or assemble.

- Place the license at the **repo root** as `gridgain-license.xml` — a GridGain 8 **XML** license
  (v2.1), *not* the GridGain 9 JSON form. It is **gitignored**; obtain it from your GridGain contract.
- `gridgain.properties` (repo root, committed) is the single place that names the license location.
  The build reads it and passes the resolved URL to every `run`/`test` JVM. Override ad hoc with
  `-Dgridgain.license.url=<url>` or `GRIDGAIN_LICENSE_URL=<url>`.

Without a valid license, node start fails with `ProductLicenseException`. See
[docs/BUILDING.md](docs/BUILDING.md#gridgain-license) and [docs/PROBLEMS.md](docs/PROBLEMS.md) P5.

## Infrastructure

Bring up MariaDB and Tigase (both required for the integration tests and a full run):

```sh
docker compose up -d
```

## Build

```sh
./gradlew build
```

## Run a module

Bring the order up: **Exchange first** (everything routes to it), then **Pub** (needs Tigase),
then **Broker** (connects to Exchange + Pub, serves OFX), then **Investor**.

```sh
./gradlew :FxcExchange:run
./gradlew :FxcPub:run
./gradlew :FxcBroker:run
./gradlew :FxcInvestor:run
```

Each reads `conf/<component>.conf` with localhost defaults; override any key with `-Dkey=value`
(e.g. `./gradlew :FxcInvestor:run -Dmode=repl` for the interactive REPL).

## Component consoles (live demo UI)

Two components serve a dark-themed static web console over REST (DESIGN §6). Both hide their control
menu when controls are switched off, and both pull their shared theme, dropdown, D3 status indicator
and vendored D3 bundle from the `fxc-common` jar.

| Console | URL | Main pane | Dropdown controls |
|---|---|---|---|
| **FxcExchange** | http://localhost:8090/ | 1-minute candles for a selectable security, translucent volume underlay in the bottom 20%, right-side volume-by-price histogram; live over a WebSocket when the interval end is left open | Stop/start trading (all symbols or just the selected one), clear the order book |
| **FxcBroker** | http://localhost:8083/ | Last-sale ticker from the exchange feed, plus one line per *agent* of cumulative trades vs. session P&L over a rolling 15-minute window, with a legend and a table view | Stop/start trading |
| **Locust load harness** | http://localhost:8089/ | Live request rate, latency, and business outcomes for the investor workload, broken out per investor type. **Starts idle — press Start.** | Start/stop the run and change users, spawn rate, or **how many of each investor type** — all while it runs |

REST surfaces: exchange `/api/candles`, `/api/symbols`, `/api/status`, `/api/book`, `/api/config`
(+ `POST /api/session/halt|open`, `/api/book/clear`); broker `/api/status`, `/api/accounts`,
`/api/lastsale`, `/api/pnl`, `/api/config` (+ `POST /api/trading/start|stop`, and
`POST /api/accounts?clientId=…` which is how each agent opens its own account —
FxcBroker/docs/stories/004).

The control endpoints mutate live trading and are **unauthenticated** — fine for a localhost demo, not
for anything else (DESIGN §7.8). Set `feed.controls.enabled = false` (exchange) or
`web.controls.enabled = false` (broker) to serve a read-only monitor console instead.

Stories: `FxcExchange/docs/stories/002`, `FxcBroker/docs/stories/002`, `fxc-common/docs/stories/001`.

## Demo (end-to-end)

`scripts/demo.sh` brings up MariaDB + Tigase, starts all three backend components in dependency
order, seeds two investor accounts (cash + ACME shares), runs two autonomous liquidity-managed
`booker` agents, and starts the Locust load harness. Each fill is drop-copied to FxcPub, published to
the broker's XMPP feed, and consumed back by the investors subscribed to that feed, which folds it into
the last sale they price against — the whole loop, live.

That last leg is **silent**: `FeedClient` folds each `FILLED: ...` status into the agent's `MarketView`
without logging it, so the agent logs show order flow, not feed traffic. To see the leg asserted
explicitly, run `EndToEndDemoIT` (below).

**It starts cold, and runs continuously until you press Ctrl-C.** The exchange boots with the market
**halted** and the Locust harness boots **idle**, so both starts are yours to make:

1. `scripts/demo.sh` brings the stack up and waits, printing where to open the market.
2. Open it — `http://localhost:8090/` → Controls → Start trading (or
   `curl -X POST localhost:8090/api/session/open`). The script sees it and starts the two
   market-making agents; fills begin.
3. Press **Start** at `http://localhost:8089/` to add the investor swarm on top.

`--batch` opens the market itself, because the unattended smoke test must not need a human.

```sh
scripts/demo.sh               # continuous; consoles + Locust UI live (brings up docker itself)
scripts/demo.sh --batch       # bounded: 20 ticks per agent, then exits (smoke test)
scripts/demo.sh --no-load     # continuous, without the Locust harness
scripts/demo.sh --down        # additionally `docker compose down` on exit
```

The two **Java** agents use `booker` rather than `rando` because `booker` is liquidity-managed — it
scales buying to available cash and sells to keep a cash floor, so the flow is sustainable
indefinitely. `rando` is naive by design and a continuous run of *only* it would exhaust one side of an
account and degrade into rejections. Override with `FXC_AGENT_STRATEGY`.

**Every agent trades its own account, funded with cash.** The two Java agents and each Locust investor
open one at startup (`POST /api/accounts`, idempotent per client id), so the broker console's
per-account P&L is one agent's rather than a blend — and `rando` draws down only its own balance.
Opened accounts get **cash only**: the tradable float is what the seeded dev accounts hold
(`account.seedShares`), so adding investors brings capital to the market instead of inflating the
supply of stock (docs/PROBLEMS.md P19). The seeded dev accounts
remain for the REPL and `-Daccount=`. Set `account.open.enabled = false` (broker) to go back to
sharing.

The **Locust** investors run **one of each type** by default — `rando`, `booker` and `bookfish` — and
the number of each is a live control in the UI (`loadgen/README.md`). So the demo does show `rando`
being rejected sometimes: those rows are broken out by reason and deliberately not counted as
failures, because a broker saying no is the system working. `bookfish` also shows
`skipped:no-edge` rows — it waits for an advantage rather than guessing
(`FxcInvestor/docs/stories/003`). Set `FXC_LOADGEN_STRATEGY=booker` for the old single-strategy
workload, or `FXC_LOADGEN_MIX_*` for a different starting mix.

Two notes if you are editing while a demo is running:

- **Do not rebuild mid-run.** Any `./gradlew` invocation that rewrites a jar (for example after
  touching `fxc-common`) replaces it underneath the already-running JVMs, which then fail to lazily
  load classes they had not yet touched (`NoClassDefFoundError`). Run `./gradlew assemble` *before*
  starting the demo.
- **Launcher JDK must be 21.** JDK 25 crashes the Gradle Kotlin-DSL parser. `.sdkmanrc` pins it if you
  use SDKMAN; otherwise `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` first.

### Starting from a clean market

A demo accumulates. A few minutes of continuous flow archives hundreds of thousands of rows to
MariaDB, and the exchange chart, the broker's P&L curves and `bookfish`'s volume-by-price all read
that history back — so the next demo opens on the market the last one left behind.

**Stop the demo first**, then:

```sh
scripts/reset.sh              # truncate the archives, drop the GridGain work dirs and demo logs
scripts/reset.sh --dry-run    # show exactly what would go, change nothing
scripts/reset.sh --hard       # also destroy the MariaDB volume (Tigase's accounts go with it)
```

The default takes about a second and keeps the container, the volume and Tigase's XMPP accounts.
`--hard` is `docker compose down -v`, so the next `docker compose up` re-creates the schemas and
re-runs `tigase-init` — a minute or so. Either way the seeded balances come back, because FxcBroker
re-seeds them from `account.*` at every start.

The GridGain side needs no wiping *per se*: the hot tables are in-memory (`gridgain.persistence.enabled
= false`) and die with the JVMs. What the script removes is each node's work directory
(`$TMPDIR/fxc-{exchange,broker,pub}-ignite`) — cluster metadata that outlives a run and is the kind of
stale state that makes a later startup fail for no visible reason.

The script **refuses to run while the demo is up**: truncating archives under a live `ArchiveService`,
or deleting a work directory under a live GridGain node, fails in ways that look like component bugs.

Backend logs land in `build/demo-logs/`. The deterministic, CI-friendly proof of the same
Investor → Broker → Exchange → fill → Pub → Investor-feed path is the JUnit orchestrator
`com.fxc.investor.EndToEndDemoIT`:

```sh
docker compose up -d                                   # Tigase must be reachable
./gradlew :FxcInvestor:test --tests '*EndToEndDemoIT'  # skips gracefully if Tigase is down
```

## Tests

```sh
./gradlew test
```

Integration tests that need infrastructure **skip** (they don't fail) when it's unreachable:
the archival tests (`*ArchiveIntegrationTest`) need MariaDB on `127.0.0.1:3306`; the XMPP-feed and
end-to-end tests (`PubIntegrationIT`, `FeedIngestionIT`, `EndToEndDemoIT`) need Tigase on
`127.0.0.1:5222`. Run `docker compose up -d` first to exercise them.

## SaltStack formula

This repo is *also* a conformant SaltStack formula named `fxc` (state tree in `fxc/`), for
deploying the same components onto a fleet of AWS EC2 minions via `salt-cloud` — a separate,
native (JDK + systemd, no Docker) path from the `docker-compose.yml` demo above. See
[fxc/docs/README.md](fxc/docs/README.md) for usage, [fxc/docs/DESIGN.md](fxc/docs/DESIGN.md) for
the design, and root [docs/PLAN.md](docs/PLAN.md) Phase 8 for status.
