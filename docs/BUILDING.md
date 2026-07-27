# Building FXC

How to build the FXC components, what tools you need, and where the build products land. For the
per-component JDK rules and the runtime infrastructure, see the root [README](../README.md).

## Toolset requirements (minimum)

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **21** | The Gradle launcher **must** run on JDK 21. Building on JDK 25 crashes the Kotlin-DSL parser (`IllegalArgumentException: 25.0.3`). All modules also *compile/target* Java 21 via the Gradle toolchain. |
| **Gradle** | 8.11.1 | Do not install it — use the checked-in wrapper (`./gradlew`); it downloads the pinned version on first run. |
| Docker + Docker Compose | any recent | **Only for running / integration tests**, not for building. Provides MariaDB + Tigase. |

Nothing else is required to build: dependencies (GridGain 8 Ultimate Edition, QuickFIX/J, OFX4J,
Smack, MariaDB Connector/J, HikariCP, Gatling) resolve from Maven Central and the GridGain Nexus repo
declared in the root `build.gradle.kts`.

> **Compiling needs no license; *running* a GridGain component does.** See
> [GridGain license](#gridgain-license) below — a valid `gridgain-license.xml` must be present to
> start a node (i.e. for `./gradlew test` and `:*:run`), but not to `compileJava`/`assemble`.

### Selecting JDK 21

If your default `java` is newer than 21 (e.g. 24/25), point the Gradle launcher at a JDK 21:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)      # macOS
# or, with SDKMAN (an .sdkmanrc is present):
sdk env
# or pin durably in gradle.properties:
#   org.gradle.java.home=/path/to/jdk-21
```

Tigase is the one exception to "JDK 21 everywhere" — it runs JDK 17 **inside its own container** and
is never built or launched from the host JVM.

## Modules

`settings.gradle.kts` aggregates five modules:

- **`fxc-common`** — shared library (`java-library` plugin): instrument model, OFX codec, FIX
  dictionary, config, the `ColdStore` MariaDB helper. Produces a plain jar; not runnable.
- **`FxcExchange`, `FxcBroker`, `FxcPub`, `FxcInvestor`** — runnable components (`application`
  plugin). Each produces a jar, a start script, and a distribution archive.

## Build commands

Run from the repository root.

```sh
# Full build: compile every module, run unit + integration tests, assemble all artifacts.
./gradlew build

# Compile only (no tests, no packaging) — fastest correctness check.
./gradlew compileJava compileTestJava

# Assemble artifacts (jars, scripts, dist archives) without running tests.
./gradlew assemble

# Build a single component and its dependencies (e.g. the exchange).
./gradlew :FxcExchange:build

# Run the test suites (see the caveat below).
./gradlew test

# Remove all build outputs.
./gradlew clean
```

### GridGain license

The GridGain-backed components (FxcExchange/FxcBroker/FxcPub) run **Ultimate Edition**, which needs a
signed license to start a node. This affects **running** — `./gradlew test` (most GridGain tests boot
an embedded node) and `:*:run` — not plain compilation.

- Put the license at the **repo root** as `gridgain-license.xml` (a GridGain 8 **XML** license, v2.1;
  *not* the GridGain 9 JSON form). The file is **gitignored** — obtain it from your GridGain contract.
- `gridgain.properties` (repo root, committed) is the single source of truth for its location
  (`gridgain.license.file=gridgain-license.xml`). The build reads it and passes the resolved
  `file:///` URL to every `run`/`test` JVM as `-Dgridgain.license.url`. Point elsewhere by editing
  that key, or override per-invocation with `-Dgridgain.license.url=<url>` /
  `GRIDGAIN_LICENSE_URL=<url>`.

Without a valid license, node start fails with `ProductLicenseException` (and the GridGain
integration/unit tests that boot a node fail rather than skip).

### Tests and infrastructure

Unit tests need nothing. **Integration tests are self-skipping**: they check for their dependency
and skip (they do **not** fail the build) when it is unreachable —

- `*ArchiveIntegrationTest`, `InvestorStoreIT`, `FeedHttpServerIntegrationTest` (and other DB-backed
  tests) need **MariaDB** on `127.0.0.1:3306`.
- `PubIntegrationIT`, `FeedIngestionIT`, `EndToEndDemoIT` need **Tigase** on `127.0.0.1:5222`.

To exercise them, bring the infra up first:

```sh
docker compose up -d
./gradlew test
```

### Gatling harness (opt-in, not part of `build`)

The FxcInvestor load/bulk-simulation harness lives in a separate `gatling` source set and only runs
on demand (needs a running broker):

```sh
./gradlew :FxcInvestor:gatlingRun \
  -Dgatling.simulationClass=com.fxc.investor.sim.FxcInvestorSimulation
```

## Build products — what and where

Each module writes under its own `build/` directory. After `./gradlew build`:

| Product | Location | Notes |
|---|---|---|
| Component jar | `<Module>/build/libs/<Module>-0.1.0-SNAPSHOT.jar` | Compiled classes for that module only (not a fat jar). |
| Shared library jar | `fxc-common/build/libs/fxc-common-0.1.0-SNAPSHOT.jar` | Consumed by the components. |
| Start scripts | `<Component>/build/scripts/<Component>` and `<Component>.bat` | Launcher scripts (Unix + Windows) with the classpath wired up. |
| Distribution archives | `<Component>/build/distributions/<Component>-0.1.0-SNAPSHOT.{tar,zip}` | Self-contained bundle: the component jar + all runtime dependency jars under `lib/`, plus the start scripts under `bin/`. This is the shippable artifact. |
| Test reports | `<Module>/build/reports/tests/test/index.html` | HTML results; raw XML under `build/test-results/test/`. |
| Gatling reports | `FxcInvestor/build/reports/gatling/.../index.html` | Only after `gatlingRun`. |

The `application` plugin also provides a JVM-embedding note for the GridGain components
(FxcExchange/FxcBroker/FxcPub): their generated start scripts include the required Ignite
`--add-opens` flags (`applicationDefaultJvmArgs`), so the distribution archives run out of the box.

### Running from the products

```sh
# From a start script (after ./gradlew assemble or build):
./FxcExchange/build/scripts/FxcExchange

# Or unpack a distribution archive:
tar xf FxcExchange/build/distributions/FxcExchange-0.1.0-SNAPSHOT.tar
./FxcExchange-0.1.0-SNAPSHOT/bin/FxcExchange
```

> **License from a packaged dist.** The generated start scripts carry the `--add-opens` flags but
> **not** the `-Dgridgain.license.url` system property (that is injected only for the Gradle
> `run`/`test` tasks). A dist therefore falls back to `GridNode`'s default — `gridgain-license.xml`
> resolved against the **current working directory**. Run from a directory that contains the license,
> or set `GRIDGAIN_LICENSE_URL` / `-Dgridgain.license.url=<url>` explicitly.

During development it is usually simpler to run in place with `./gradlew :FxcExchange:run` (see the
README's "Run a module" and "Demo" sections for start-up order and the end-to-end walkthrough).
