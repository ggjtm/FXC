# FXC Problems & Risk Log

A running log of significant risks, blockers, and open concerns. Lighter, non-blocking open
items live in [DESIGN.md §6](DESIGN.md#6-open-items-flagged-not-blocking); this file tracks the
issues serious enough to change a decision. Each entry has a status: **OPEN**, **RESOLVED**, or
**MITIGATED**.

---

## P1 — Apache Vysper is unviable as the FxcPub XMPP core — **RESOLVED** (replaced by Tigase)

**Discovered:** 2026-07-13, during reference-doc gathering (see `.reference/vysper-xmpp/`).

**Concern.** The original design chose Apache Vysper as FxcPub's embedded XMPP server. Research
found it effectively abandoned and a poor fit for a Java 21 target:

- **Stale to the point of abandonment.** Vysper's only published release is **0.7, from
  February 2011** (`org.apache.vysper:vysper-core:0.7`). No active release cadence; it is a
  dormant Apache MINA subproject.
- **Java 21 viability unverified / doubtful.** It is a Java 5/6-era artifact carrying old MINA
  and BouncyCastle transitive dependencies. Booting on JDK 21 is expected to hit TLS-handshake
  and reflective-access ("illegal access") friction, and was never validated.
- **Partial XEP-0060 (PubSub).** Publish/subscribe/unsubscribe and node create/delete work, but
  there is **no auto-create, no node configuration, no collection nodes, no item retraction, and
  no presence-based delivery** — several of which the timeline/feed model would want.
- **Operational friction.** Requires a TLS certificate before `start()` even for local dev, and
  the `PublishSubscribeModule` ships in `vysper-extensions`, not `vysper-core`.

**Impact.** FxcPub is one of the four core components; an XMPP core that may not start on the
target JVM is a project-level risk, not a detail.

**Resolution.** Replace Vysper with **Tigase XMPP Server** for FxcPub (decision 2026-07-13).
Tigase is actively maintained, designed for scale, and ships a far more complete XEP-0060 PubSub
component. FxcPub retains GridGain for hot application state and MariaDB for durable + cold data
(and, conveniently, MariaDB is also Tigase's own native repository store). See
[DESIGN.md §4.3](DESIGN.md). The Vysper reference notes under `.reference/vysper-xmpp/` are now
historical, except `smack-client.md` (the Smack XMPP *client* is still used by FxcBroker and
FxcInvestor regardless of server choice).

---

## P2 — Tigase adoption carries its own open questions — **OPEN**

Follow-on concerns created by the P1 resolution. Reference research (`.reference/tigase-xmpp/`)
settled some; the rest are for a Phase-0 spike before FxcPub implementation (Phase 3).

- **Deployment model — RESOLVED: stock, standalone, external service.** Research found **no
  verified public Maven coordinate or supported API for embedding Tigase in-process**. Tigase
  (current **8.4.1**) ships as a distributable server package (`*-dist`) plus Docker image
  `tigase/tigase-xmpp-server`, started via `scripts/tigase.sh` (main class `tigase.server.XMPPServer`).
  **Decision:** FxcPub runs Tigase **100% unmodified** (no custom plugins/components/patches;
  configured only via supported `config.tdsl`/`dataSource`) as an **external service** — a
  docker-compose service in dev — *not* bootstrapped inside the FxcPub JVM. **FxcPub's own code
  interacts with Tigase strictly as a standard XMPP client** (Smack). The FxcPub deliverable is
  the GridGain + XMPP-client application layer plus Tigase configuration, not a server. See
  DESIGN §4.3 for the rationale (upgrade safety, AGPL isolation, portability).
- **JDK — Tigase must run on 17, RESOLVED.** Tigase 8.4.1 bundles a Groovy whose ASM cannot read
  Java 21 class files (`Unsupported class file major version 65`), so the server fails to start on
  JDK 21/25. Our Tigase image is based on `eclipse-temurin:17-jre`. Because Tigase runs as a
  separate container, FXC's own components remain on JDK 21 — no conflict. (See the JDK table in the
  [README](../README.md) for the full per-process JDK matrix.)
- **License — AGPLv3. DECISION: ACCEPTED via the unmodified-server strategy (2026-07-13).** Tigase
  XMPP Server is **AGPLv3** (separate commercial license available). Jeremy accepted the approach:
  run Tigase **100% unmodified** to avoid triggering AGPLv3 constraints — customization is limited
  to **server-side configuration** (`config.tdsl`/`dataSource`) and **custom client-side features**
  (Smack), with no source modified or distributed (DESIGN §4.3). This lifts the earlier hold; the
  Phase-0/Phase-3 Tigase spike may now run. Legal confirmation still advised, but no covered work
  is modified or conveyed.
- **Integration seam (XMPP-client side).** Decide how FxcPub's XMPP-client services publish into
  and read from Tigase PubSub — the documented paths are a **trusted/admin Smack client
  connection** or **ad-hoc commands**. (Tigase's own REST API is a candidate for the *deferred*
  Mastodon gateway, not the core.) See `.reference/tigase-xmpp/pubsub-xep0060.md` and DESIGN §4.3.
- **Mastodon REST — deferred (not a P2 concern).** The Mastodon-compatible REST surface is no
  longer part of FxcPub; it is a late-phase XMPP↔Mastodon gateway addon (DESIGN §6.2, PLAN Phase 7).
- **GridGain does not replace Tigase's repository.** Tigase persists its own XMPP state (users,
  auth, offline, pubsub nodes/items) via its JDBC repository → MariaDB. GridGain holds FXC's
  *application-domain* hot state (timeline projections, follow-graph cache, FIX-gateway rendering),
  consistent with FxcBroker/FxcExchange. A GridGain-backed custom Tigase `DataSource` is a
  possible future optimization, **not** a goal.

### Phase-3 spike outcome (2026-07-13) — **Tigase running; gate cleared**

The Tigase server is up and reachable (c2s `127.0.0.1:5222` open; also 5223/5269/5280/8080). Getting
there surfaced three real issues, all resolved without modifying Tigase's code:

- **Official image is a non-runnable skeleton.** `tigase/tigase-xmpp-server:8.4.1` is a bare JRE
  (Java 25) with empty `jars/`/`conf/` volume dirs and no launch script — it does not self-run.
  **Fix:** `docker/tigase/Dockerfile` builds a thin image that fetches the **genuine unmodified
  community `-dist` tarball** (GitHub release) and runs it via the shipped `scripts/tigase.sh`.
- **JDK: must be 17, not 21+.** Tigase 8.4.1 bundles a Groovy whose ASM can't read Java 21 class
  files (`Unsupported class file major version 65`). **Fix:** base the Tigase image on
  `eclipse-temurin:17-jre`. It runs as a separate container, so FXC's own components stay on 21.
  (See the JDK table in the [README](../README.md).)
- **MariaDB FK-signedness incompatibility.** Tigase's MySQL schema declares
  `tig_broadcast_recipients.jid_id` as signed `BIGINT` but parent `tig_broadcast_jids.jid_id` as
  `BIGINT UNSIGNED`; MySQL tolerates it, MariaDB rejects it (errno 150), aborting the schema load.
  **Fix (Jeremy's call):** patch that one column to `BIGINT UNSIGNED` via a one-shot init step
  (`docker/tigase/init-schema.sh`, run as the `tigase-init` compose service) that patches a
  writable copy at runtime and loads the schema — the vendor files baked in the image stay pristine.

Remaining: the Smack publish/subscribe round-trip is validated by the FxcPub integration test
(Phase 3), which runs against this container.

### Phase-0 spike status (2026-07-13) — superseded by the Phase-3 outcome above

- **Docker image name corrected.** The reference docs said `tigase/tigase-server`; the actual
  official image is **`tigase/tigase-xmpp-server`** (tag `8.4.1` confirmed to exist on Docker Hub).
  Fixed in DESIGN, PLAN, and `.reference/`. (`github.com/tigase/tigase-server` remains the correct
  *source-repo* URL — only the Docker image name was wrong.)
- **Infra scaffolded.** `docker-compose.yml` defines both MariaDB and Tigase; `docker/tigase/config.tdsl`
  is a best-effort stock config (MariaDB `dataSource`, `pubsub`/`muc`/`message-archive` components,
  vhost `fxc.local`); `docker/mariadb/init/01-databases.sql` creates `tigasedb` + a `tigase` user.
- **MariaDB half verified.** `docker compose up mariadb` comes up healthy; all four component schemas
  and `tigasedb` exist; the `fxc` user connects; the per-component `schema.sql` stubs apply cleanly.
- **⏳ NOT yet done — the Tigase run + Smack round-trip.** Pulling/running the Tigase container,
  loading its repository schema, creating a pubsub node, and completing a Smack login +
  publish/subscribe round-trip are **pending the AGPLv3 acceptance decision** below (running the
  image is using AGPLv3 software). The `config.tdsl` schema-bootstrap mechanism for 8.4.1 is also
  unverified and may need adjustment during the run. **This is the remaining blocking gate for
  FxcPub (Phase 3); it does not block Phases 1–2.**

---

## P3 — OFX4J server-side support is thin — **MITIGATED** (design accounts for it)

See [DESIGN.md §6.4](DESIGN.md). The entire OFX4J server contract is one method
(`OFXServer.getResponse(RequestEnvelope)`); FxcBroker hand-builds every response aggregate and
bypasses the `javax`/Jakarta `OFXServlet` on Java 21. Additionally, OFX4J's unmarshaller only
resolves aggregate classes under `com.webcohesion.ofx4j.*`, constraining where the custom
order-entry message set can live. Not a blocker — folded into the FxcBroker design and estimates.

---

## P4 — GridGain 8 artifacts are not on Maven Central — **MITIGATED**

See [DESIGN.md §6.7](DESIGN.md). GridGain 8 artifacts (any edition) are published only to the
GridGain Nexus repository, which the root `build.gradle.kts` declares. Embedded GridGain on JDK 21
also requires a specific `--add-opens` flag set (`igniteJvmArgs`; see
`.reference/gridgain/README.md`).

**Update (2026-07-27).** The project moved from GridGain 8 **Community Edition** to **Ultimate
Edition** (`org.gridgain:gridgain-ultimate`), and the API-compatible Apache Ignite 2.x drop-in
fallback was **removed** — so the Nexus repo is now a hard requirement and the node needs a signed
license. See **P5**.

---

## P5 — GridGain Ultimate Edition: licensing & the CE→Ultimate switch — **RESOLVED** (2026-07-27)

**Change.** Switched the hot-state engine from GridGain 8 Community Edition
(`org.gridgain:ignite-core`) to **Ultimate Edition** (`org.gridgain:gridgain-ultimate`, which
transitively pulls in `ignite-core` + `gridgain-core`) and deleted the Apache Ignite 2.x fallback
from the version catalog. Ultimate requires a signed license, wired into each component's `GridNode`
via `GridGainConfiguration.setLicenseUrl(...)` on the `IgniteConfiguration` plugin list.

**Configuration (single source of truth).** `gridgain.properties` at the repo root names the license
file (`gridgain.license.file=gridgain-license.xml`); the root `build.gradle.kts` reads it once,
resolves it to an absolute `file:///` URL against the repo root, and passes it to **every** Gradle
`run` and `test` JVM as `-Dgridgain.license.url`. `GridNode.licenseUrl()` also honours that system
property (and the `GRIDGAIN_LICENSE_URL` env var) and keeps a bare-filename default as a last-resort
fallback for non-Gradle (packaged-dist) launches. The license file itself is **gitignored**.

**Lessons learned** — each was a distinct failure that surfaced only at node start, never at compile
time, so they were found one layer at a time by re-running the suite:

- **`setLicenseUrl` wants a URL, not a path.** A bare `gridgain-license.xml` throws
  `MalformedURLException`. Resolve paths to a `file://` URL before handing them over.
- **`File.toURI()` ≠ `Path.toUri()`.** `File.toURI()` emits a single-slash `file:/…` (no authority);
  `Path.toUri()` emits the canonical `file:///…`. A scheme guard that only checks for `"://"` misses
  the single-slash form and re-mangles it — match a leading `scheme:` instead, and prefer
  `Path.toUri()` when generating the URL.
- **A forked Gradle JVM's CWD is the *subproject* dir, not the repo root.** A relative license path
  resolves against `FxcBroker/` (etc.), where the root-level license does not exist
  (`FileNotFoundException`). Pass an absolute URL from the build; do not rely on the CWD default.
- **GridGain 8 licenses are XML; GridGain 9 licenses are JSON.** A GridGain 9 `gridgain-license.json`
  fed to the GG8 engine fails with `ProductLicenseException` → `Unexpected character '{' … expected
  '<'` (its `GridEntLicenseProcessor` parses XML). Use the `gridgain-license.xml` (v2.1) form for
  8.9.x.
- **Kotlin-DSL gotcha:** with the `java` plugin applied, the bareword `java` resolves to Gradle's
  Java *extension*, so `java.util.Properties` fails to compile (`Unresolved reference: util`). Add an
  explicit `import java.util.Properties` at the top of `build.gradle.kts`.

**Verification.** Full suite green (64 tests, 0 failures) and `:FxcExchange:run` boots the licensed
node (`FxcExchange started.`) with the license resolved from `gridgain.properties`. The build
launcher must still be JDK 21 (JDK 25 crashes the Gradle Kotlin-DSL parser — see the README JDK
table).
