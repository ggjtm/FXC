# FXC

A multi-module Gradle project of four independent system components plus a shared library. The
GridGain-backed components (Exchange, Broker, Pub) hold hot state in an embedded in-memory
GridGain 8 node and archive cold/terminal data to MariaDB; FxcInvestor is a MariaDB-backed agent
and REPL client.

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
  Includes an opt-in Gatling harness for bulk simulation.
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
| **FxcInvestor Gatling harness** | **21** | Gradle `gatlingRun` task | Same toolchain as the module. Opt-in only (`src/gatling/java`); not part of `build`/`check`. |
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

## Demo (end-to-end)

`scripts/demo.sh` is the full Phase-6 walkthrough. It brings up MariaDB + Tigase, starts all three
backend components in dependency order, seeds two investor accounts (cash + ACME shares), then runs
two autonomous `rando` agents whose orders cross to produce fills. Each fill is drop-copied to
FxcPub, published to the broker's XMPP feed, and echoed back by the investors reading that feed —
the whole loop, live:

```sh
docker compose up -d          # or let the script do it
scripts/demo.sh               # leaves infra up on exit; use --down to also stop it
```

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
