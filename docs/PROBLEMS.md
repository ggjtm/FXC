# FXC Problems & Risk Log

A running log of significant risks, blockers, and open concerns. Lighter, non-blocking open
items live in [DESIGN.md §7](DESIGN.md#7-open-items-flagged-not-blocking); this file tracks the
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
  longer part of FxcPub; it is a late-phase XMPP↔Mastodon gateway addon (DESIGN §7.2, PLAN Phase 7).
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

See [DESIGN.md §7.5](DESIGN.md). The entire OFX4J server contract is one method
(`OFXServer.getResponse(RequestEnvelope)`); FxcBroker hand-builds every response aggregate and
bypasses the `javax`/Jakarta `OFXServlet` on Java 21. Additionally, OFX4J's unmarshaller only
resolves aggregate classes under `com.webcohesion.ofx4j.*`, constraining where the custom
order-entry message set can live (DESIGN.md §7.4). Not a blocker — folded into the FxcBroker design and estimates.

---

## P4 — GridGain 8 artifacts are not on Maven Central — **MITIGATED**

See [DESIGN.md §3.1](DESIGN.md) and [BUILDING.md](BUILDING.md#gridgain-license). GridGain 8 artifacts (any edition) are published only to the
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

---

## P6 — `HttpServer` prefix matching silently shadowed a control endpoint — **RESOLVED** (2026-07-28)

**Discovered:** 2026-07-28, while building the DESIGN §6 consoles — by a failing test, not by review.

**Root cause.** The JDK `HttpServer` dispatches to the **longest matching prefix**, not to an exact
path. Registering a read-only `GET /api/book` and a mutating `POST /api/book/clear` looks unambiguous,
but as soon as the control endpoints were switched off (`feed.controls.enabled = false`) the
`/api/book/clear` context disappeared and the request fell through to `/api/book` — a GET-only handler,
which answered **405 method not allowed**. The same shape applied to the catch-all static handler
mounted at `/`: any POST to an unregistered API path reached it and was rejected as a method error.

**Impact.** Two misleading signals, both in the direction that costs an operator time: a `405` on
`/api/book/clear` implies "the endpoint exists, use a different verb" when in fact the whole capability
is disabled, and a GET-only handler would otherwise have served `/api/book/anything` as though it were
`/api/book`.

**Resolution.** `HttpJson.requireExactPath` — each API handler declares the one path it owns and
answers 404 for anything else. Crucially it is checked **before** the method gate, which would
otherwise mask the 404. `StaticAssets.handle` was reordered for the same reason: resolve the path
first, and only gate the method for paths it actually serves.

**Lesson.** With prefix-matched routing, "the endpoint is not registered" and "the endpoint rejects
your method" are indistinguishable unless the handler asserts its own path. Any nested resource
(`/x` plus `/x/action`) needs the exact-path check, and the check has to come before method gating.

**Verification.** `StaticAssetsTest` (405 for a write to a served page, 404 for a write to an unserved
path) and `ExchangeControlApiTest.controlsCanBeDisabledLeavingAReadOnlyConsole` (404 on both POST
paths). Both failed before the fix.

---

## P7 — DESIGN §6 renumbered every "§6.x" cross-reference in the repo — **RESOLVED** (2026-07-28)

**Discovered:** 2026-07-28, while writing the §6 consoles.

**Concern.** Inserting `## 6. Live Demo UI` pushed the old `## 6. Open items` to §7, so every existing
`DESIGN §6.N` pointer — derivatives §6.3, OFX order entry §6.4, OFX4J §6.5, FX-in-OFX §6.6, auth
realism §6.7 — silently began resolving to the wrong section. The change had renumbered two pointers
in this file and missed the rest: **~20 references across `docs/PLAN.md`, this file, all four component
`PROBLEMS.md`/`PLAN.md` files, a story doc, and six Java files.**

**Impact.** Cross-references that point somewhere plausible but wrong are worse than dangling ones —
`DESIGN §6.3` now landed inside "Live Demo UI" instead of the derivatives extension points, and nothing
would ever fail to reveal it. A further wrinkle: old §6.8 (GridGain artifact access) was *replaced*
rather than moved, so P4's pointer had no valid target in the open-items list at all.

**Resolution.** Mapped `§6.N → §7.N` across every markdown and source file for the items that still
exist, and repointed P4 at §3.1 + `BUILDING.md#gridgain-license`, where the GridGain artifact and
license material actually lives now. The new §6 subsections (§6.1–§6.5, the last added later for the
Locust harness) are referenced only by the new code and the new stories.

**Lesson.** Inserting a numbered top-level section in DESIGN.md is a repo-wide rename, not a local
edit: the section numbers are used as identifiers in prose, in component docs, and in javadoc, and no
build step validates them. Prefer appending a section, or grep `§<n>` across the whole tree before
renumbering — including `*.java` and `*.conf`, which are easy to forget.

**Verification.** `grep -rn 'DESIGN §6\.\|DESIGN.md §6\.'` across all tracked markdown and source
returns nothing (the sole remaining hit is in gitignored `.reference/`). Full suite green — 119 tests,
0 failures at the time; 156 Java + 105 Python as of P13.

---

## P8 — `scripts/demo.sh` could never start a component (empty-array expansion) — **RESOLVED** (2026-07-29)

**Discovered:** 2026-07-29, on the first attempt to actually run `scripts/demo.sh` end to end.

**Symptom.** Every component died immediately at startup with a Gradle error that named no component:

```
* What went wrong:
Cannot locate matching tasks for an empty path. The path should include a task name
```

**Root cause.** `start_component` built an optional array of `--args=` flags and expanded it as
`"${gradle_args[@]:-}"`. Under `set -u` that idiom is the usual way to reference a possibly-unset
array — but on an **empty** array it does not expand to nothing, it expands to **one empty-string
word**. Since all three call sites pass no extra system properties, the array was always empty, so
the launched command was effectively:

```
./gradlew :FxcExchange:run ""
```

and Gradle rejected the empty task path. Confirmed directly:
`a=(); set -- "${a[@]:-}"; echo $#` → `1`, with `$1` empty.

**Impact.** The script was unusable: the exchange never reported ready, `wait_for_log` timed out, and
the exit trap tore the stack down. Pre-existing — it dates from the original orchestration commit and
is untouched by the DESIGN §6 console work; the demo path's rigorous coverage has always come from
`EndToEndDemoIT`, which is why a broken shell wrapper went unnoticed.

**Resolution.** Use the `${arr[@]+"${arr[@]}"}` form, which is the `set -u`-safe way to expand a
possibly-empty array to **zero** words:

```bash
./gradlew "${task}" ${gradle_args[@]+"${gradle_args[@]}"} >"${logfile}" 2>&1 &
```

Verified both directions: empty array → `argc=0`; populated array → `argc=2` with both flags intact.

**Lesson.** `"${arr[@]:-}"` and `${arr[@]+"${arr[@]}"}` are not interchangeable. `:-` substitutes a
default *value* (the empty string) and therefore always yields at least one word; `+` tests whether
the array is set and expands to nothing when it is empty. Anywhere an optional argument list is
forwarded to a command, only the `+` form is correct. The two other `"${x[@]:-}"` uses in this script
(the `PIDS` cleanup loop and the `for a in` loop) survive only because each guards its body with
`[[ -n … ]]`.

**Also fixed in the same pass:** the script's exit trap stopped the components as soon as the agents
finished — which, now that the components serve the §6 consoles, took the consoles down before anyone
could open them. Added `--keep`, which blocks after the agents complete so the consoles stay live.

---

## P9 — FxcPub's configured XMPP password never matched the provisioned one — **RESOLVED** (2026-07-29)

**Discovered:** 2026-07-29, running `scripts/demo.sh` for the first time (after P8 unblocked it).

**Symptom.** FxcPub started its GridGain node, then died before opening its FIX acceptor:

```
Exception in thread "main" org.jivesoftware.smack.sasl.SASLErrorException:
        SASLError using SCRAM-SHA-1: not-authorized
```

**Root cause.** `docker/tigase/init-schema.sh` provisions **all four** service accounts
(`admin`/`broker`/`pub-service`/`investor`) with a single shared dev password —
`TIGASE_SVC_PASS`, default **`secret`**. But two component configs used the *account name* as the
password instead:

| conf | password sent | provisioned | |
|---|---|---|---|
| `FxcInvestor/conf/fxcinvestor.conf` | `secret` | `secret` | ✓ |
| `FxcPub/conf/fxcpub.conf` | `pub-service` | `secret` | ✗ blocked the demo |
| `FxcBroker/conf/fxcbroker.conf` | `broker` | `secret` | ✗ latent (keys not read yet) |

**Why 119 green tests missed it.** The Pub integration tests pass credentials **in code** —
`PubIntegrationIT` connects with `"pub-service", "secret"` and `PubSubRoundTripIT` with
`"broker", "secret"` — so they exercise the correct password and never read `fxcpub.conf`. `Main` is
the only consumer of that file, and only `scripts/demo.sh` runs `Main`. With the demo script itself
broken (P8), nothing had ever executed this path. Two defects hid each other.

**Compounding factor — a silent provisioning check.** `init-schema.sh` printed `schema load OK` while
Tigase's own output said:

```
Adding XMPP admin accounts	error
        Message: Database schema is invalid
```

because the script's only failure test grepped for an error on the *Core schema* line. So even a
genuine failure to create the accounts was reported as success, and would surface much later as a
`not-authorized` in whichever component logged on first.

**Resolution.**
1. `FxcPub/conf/fxcpub.conf` and `FxcBroker/conf/fxcbroker.conf` now use `secret`, each with a comment
   naming `docker/tigase/init-schema.sh` as the source of truth and stating that the password is *not*
   the account name.
2. `init-schema.sh` now reports the account step explicitly — either confirming which accounts were
   provisioned, or emitting a loud warning with a verification query. Deliberately a **warning, not a
   hard failure**: on an already-provisioned volume the tool reports that step as an error on every
   run even though the accounts are fine, so failing would break idempotent `docker compose up`.

**Lesson.** This is the same failure mode as FxcBroker's B9 (investor↔broker OFX credentials), and the
same fix — but it recurred because the coupling is only expressed in a comment. Credentials shared
between a provisioning script and a component config are a contract with no compiler and no test
behind it. Worse, **hardcoding credentials in an integration test actively hides config drift**: the
test proves the *server* accepts the password, not that the *shipped config* sends it. When a config
value has exactly one real consumer, a test that bypasses that consumer proves very little.

**Verification.** `scripts/demo.sh --keep` brings the full stack up; `pub-service@fxc.local`
authenticates and FxcPub opens its FIX drop-copy acceptor on 9878.

---

## P10 — `conf/*.conf` trailing `#` comments became part of the value — **RESOLVED** (2026-07-29)

**Discovered:** 2026-07-29, running `scripts/demo.sh` (after P8/P9 unblocked it). Both investor agents
died on startup, before placing a single order:

```
Exception in thread "main" java.lang.NumberFormatException:
        Character array is missing "e" notation exponential mark.
        at com.fxc.investor.Main.main(Main.java:44)
```

**Root cause.** `FxcConfig` is backed by `java.util.Properties`, which honours `#` as a comment
**only at the start of a line**. `FxcInvestor/conf/fxcinvestor.conf` used trailing comments, so three
values silently carried their comment text:

```
agent.strategy     = [rando          # rando (implemented); booker/bookfish are planned (docs/stories/)]
agent.seedLastSale = [42.10          # ToDo: replace with XMPP-feed-derived last sale (PLAN item 3)]
agent.ticks        = [10             # 0 = run until interrupted]
```

`Main.java:44` does `new BigDecimal(config.getString("agent.seedLastSale", …))` and threw. Had it
survived, `agent.ticks` would have failed `Integer.parseInt` two lines later, and `agent.strategy`
would not have resolved to a known strategy.

**Impact.** `FxcInvestor.Main` could never start from its own config file — the whole agent runner,
unreachable. The other three component configs were checked and are clean; only this file used
trailing comments.

**Resolution.** Moved all three comments onto their own lines, and documented the comment rule in
`FxcConfig`'s javadoc so the next editor does not reintroduce it. Verified by loading all four conf
files through `Properties` and asserting no value contains `#`, then parsing each affected value.

**Lesson.** A config format inherited from a library brings that library's lexical rules with it, and
"flat `key=value`" does not imply HOCON- or YAML-style trailing comments. The failure mode is
especially bad because it is *silent at parse time* and surfaces as a type error in unrelated code far
away. A three-line guard that rejects any loaded value containing ` #` would have caught all three at
startup.

---

## P11 — `./gradlew :X:run -Dkey=value` never reached the application — **RESOLVED** (2026-07-29)

**Discovered:** 2026-07-29, while verifying P10's fix. The investor logged
`account=000123456` despite being launched with `-Daccount=000654321`.

**Root cause.** Gradle's `-D` flags set system properties on the **Gradle daemon** JVM. The
`application` plugin's `run` task is a `JavaExec` that forks a *new* JVM and does **not** inherit
them, and the root build forwarded exactly one property (`gridgain.license.url`). `FxcConfig.find()`
consults `System.getProperty()` inside the application, which therefore never saw any override.

**Impact.** Two things that were documented as working, did not:
1. `README`'s "override any key with `-Dkey=value` (e.g. `-Dmode=repl`)" — silently ignored for `run`.
2. `scripts/demo.sh` launches its two agents as *"different account + seed so the two agents cross"*
   — but both silently fell back to the same conf values, so they produced identical order streams and
   could never cross. **The demo could not have produced a fill even with P8/P9/P10 fixed.**

**Resolution.** The root `build.gradle.kts` now forwards system properties in the FXC config
namespaces (`account`, `mode`, `agent.`, `ofx.`, `fix.`, `xmpp.`, `feed.`, `web.`, `gridgain.`,
`db.`, `archive.`) from the daemon into the forked JVM. (`sim.` was in this list for the Gatling
harness and went with it.) Deliberately an allowlist rather
than copying the whole property set, which would push the daemon's `java.home`/`user.dir` into the
child. `gridgain.license.url` is still applied last so the build's absolute, CWD-independent URL wins.

**Lesson.** "It's on the command line" is not the same as "the process can see it" once a task forks.
Anything that documents a `-D` override for a `JavaExec`-based task has to forward it explicitly —
and the failure is invisible, because the value simply falls through to its default. Four separate
defects (P8, P9, P10, P11) were stacked in this one path, each hidden behind the previous one, because
nothing but the demo script exercised it and the integration tests all construct their configuration
in code.

---

## P12 — `scripts/demo.sh` could not be shut down by a signal, and cleaned up twice when it was — **RESOLVED** (2026-07-29)

**Symptom.** Two orphaned `demo.sh` shells were found still parked hours after their runs, having
outlived every component JVM they started. `kill -INT`/`kill -TERM` against the script did nothing.
When teardown *did* eventually run, it printed `Shutting down components...` and its whole sequence
**twice**.

**Root cause, part 1 — the parking loop swallowed signals.** The script parked with:

```bash
while true; do sleep 3600; done
```

**Bash does not run a trap until the currently executing foreground command completes.** `sleep 3600`
is a foreground child, so a signal delivered to the shell sat pending for up to an hour before
`cleanup` ran. This was invisible in normal use: pressing Ctrl-C in a terminal signals the entire
foreground **process group**, `sleep` included, so the sleep dies, the loop iterates, and the pending
trap fires immediately. Only a signal aimed at the script *alone* — a supervisor, a CI harness, an
automated teardown — hits the deferral.

Proven by A/B, not inferred: with `kill -TERM`, the old idiom stayed alive with its trap never firing;
the fixed one exited promptly and ran cleanup.

**Root cause, part 2 — `trap cleanup EXIT INT TERM` fires twice.** On a signal, the INT/TERM trap runs,
and then the EXIT trap runs for the exit that follows. Cleanup is idempotent so nothing broke, but
reporting the shutdown twice reads like a fault.

**Resolution.** Park on an interruptible `wait`, and guard cleanup for idempotence:

```bash
CLEANED=false
cleanup() { [[ "${CLEANED}" == "true" ]] && return 0; CLEANED=true; ...; }

while true; do
  sleep 3600 &
  wait $! || break
done
```

`wait` *is* interruptible by a trap, so the signal is honoured immediately.

**Lesson.** This defect could only appear once the demo became long-running (P12 is a direct
consequence of the continuous-demo change) and only mattered for non-interactive teardown — which is
exactly the path a human never tests, because a human presses Ctrl-C. A script that is *meant* to be
killed should be tested with `kill`, not with Ctrl-C: the two deliver signals to different targets.

---

## P13 — the demo told the operator to watch for output that is never printed — **RESOLVED** (2026-07-29)

**Symptom.** `scripts/demo.sh` printed `Watch for 'FILLED ...' lines echoed from the FxcPub feed.` No
such line ever appears in any agent log — `grep -c FILLED build/demo-logs/investor-a.log` is 0 after
thousands of fills. `README.md` likewise claimed each fill was *"echoed back by the investors reading
that feed"*.

**Root cause.** The last leg of the loop is real but **silent**. `FeedClient` subscribes to
`feed-<brokerId>`, parses each `FILLED: <side> <qty> <symbol> @ <price>` status, and folds it into the
agent's `MarketView` — last sale for every strategy, the traded-volume histogram for `bookfish`. It
contains **zero** `println` calls, by design. The banner described an echo that was never implemented.

**Impact.** Worse than a stale comment: it aims the operator at absent evidence, so a fully working
demo looks broken at precisely the leg that is hardest to verify by eye. This is what the instruction
to evaluate the demo by reading its logs actually turned up.

**Resolution.** The banner now points at output that exists (`BUY/SELL ... -> ROUTED`, which the agents
do print) and names what prices it. README states plainly that the feed leg is silent and points at
`EndToEndDemoIT`, which asserts it deterministically.

**Lesson.** A demo's log output is an interface, and a claim about it is a testable assertion. If a doc
says "watch for X", grep for X.

---

## P14 — a Locust custom option with a `None` default can never be changed from the web UI — **RESOLVED** (2026-07-29)

**Symptom.** While building the investor-mix control (FxcInvestor/docs/stories/007), the natural way to
express "no explicit mix given" was `parser.add_argument("--mix-rando", type=int, default=None)`. The
field appeared in the UI's swarm form and accepted input, and the value never changed.

**Root cause.** `locust/web.py`'s `/swarm` handler updates `environment.parsed_options` from the form
while *preserving the existing type*, and its branch for an existing `None` writes the old value back:

```python
elif parsed_options_value is None:
    parsed_options_dict[key] = parsed_options_value   # i.e. None, discarding the submitted value
```

With no type to coerce to, locust prefers keeping `None` over guessing. The form field is rendered, the
POST carries the value, and it is dropped on arrival.

**Impact.** Silent, and specific to the control surface the whole story is about: the option works from
the CLI and from the environment, so tests and the container path pass while the browser does nothing.

**Resolution.** Every harness option has a real default of its intended type — `0` for the mix shares,
`""` for `--strategy`, `0` meaning "off" for the threshold gates. The rule is stated in the docstring in
`loadgen/locustfile.py` where the options are declared, because the next person to add one will not read
this file.

**Lesson.** When a framework converts form strings using the current value's type, `None` is not a
neutral default — it is an opt-out of being configurable at all.

---

## P15 — the Locust UI's user count lands on `users`, not `num_users` — **AVOIDED** (2026-07-29)

**Symptom.** The first cut of the investor-mix control apportioned the mix across the user count read
from `parsed_options.num_users`. It kept apportioning across the *command-line* count no matter what was
typed in the browser.

**Root cause.** Locust's `--users` option declares `dest="num_users"`, but the `/swarm` handler writes the
submitted count to a **different** key:

```python
if key == "user_count":  # if we just renamed this field to "users" we wouldn't need this
    user_count = int(value)
    parsed_options_dict["users"] = user_count
```

So after any swarm from the UI, `vars(parsed_options)` has *both* `num_users` (stale, from CLI/env) and
`users` (live). Nothing reads `users` inside locust — the runner is started from the local variable — so
the mismatch is invisible until something else tries to read the current count.

**Impact.** The mix would have appeared to work in headless runs and in tests, and quietly ignored the
browser — the one path that matters.

**Resolution.** None needed in the end: the as-built design (P16) apportions across the **live
population** it holds a registry of, not across any option, so it never reads either key. Recorded
because the next person to reach for `parsed_options.num_users` will hit this, and because it took a
browser to notice — headless runs and tests both passed.

**Lesson.** Two names for one value is a bug waiting for a second reader. The comment in locust's own
source says as much; worth reading the handler rather than the option declaration.

---

## P16 — `User.fixed_count` cannot express a *live* population mix — **RESOLVED** (2026-07-29)

**Symptom.** The investor mix (FxcInvestor/docs/stories/007) was first built the obvious way: one
`User` subclass per strategy, and a `LoadTestShape` that set `fixed_count` on each class from the
apportioned counts and returned the new total. Starting a run worked perfectly. **Changing the mix
mid-run did not.** Asking for 2/9/5 from a running 3/3/2 produced 3/8/5; asking for 8/0/0 produced
3/3/2 — unchanged. The shape's log line showed the right target, and locust logged `Ramping to 16
users`, then `All users spawned: … (8 total users)`.

**Root cause.** Three behaviours in `locust/dispatch.py` compose into a dead end:

1. `_user_gen` builds its weighted generator once as `_kl_generator((u.__name__, u.weight) for u in
   self._user_classes if not u.fixed_count)`. With every class carrying a `fixed_count`, that generator
   is **empty** and yields `None` forever — which the dispatcher reads as "no user to spawn".
2. The fixed-count branch runs only when `_try_dispatch_fixed` is set, and `new_dispatch` does **not**
   set it. It is armed in `__init__`, on a worker rebalance, and on user *removal* — so a pure scale-up
   never re-reads `fixed_count` at all.
3. Even with the flag forced on, that branch only computes `fixed_count - current` for **positive**
   misses: it tops up a shortfall and never removes a surplus. And removal (`_remove_users_from_workers`)
   pops `_active_users` LIFO, class-blind, so scaling *down* removes whoever was newest rather than
   whoever is over target.

Verified by driving a `LocalRunner` directly rather than by reading: with the flag forced on, 2/9/5 came
back as 3/8/5 (the surplus `rando` never left), and 8/0/0 came back as 3/3/2.

**Impact.** This is the feature's whole point — "control a variable number of each type of investor
**in the UI**" — so a mechanism that only works at start is not a partial win, it is the wrong
mechanism. It also fails quietly: locust logs a ramp to the requested total and then reports a different
total, with no error anywhere.

**Resolution.** Inverted the ownership. There is **one** user class, and the strategy is an *attribute*
of an investor rather than its type. Locust keeps doing what it is good at — owning the population size,
ramping, and the UI's *Number of users* — while `fxc_loadgen.mix.reassign` moves the minimum number of
**live** investors from one strategy to another, oldest kept, on a one-second timer. A strategy change
costs nothing: the investor keeps its account, its RNG, its portfolio view and its connection. As a
bonus, `--run-time` still works (a `LoadTestShape` takes over stopping a run, and would have had to
re-implement the time limit) and no locust internals are touched at all.

**Lesson.** `fixed_count` is documented as "spawned first" and its docstring even warns that the final
count is undefined if the target is too small — that is a hint that it describes a *starting*
distribution, not an invariant. When a framework's knob is only read at dispatch construction, it cannot
be a live control; find the layer that *is* re-read, or own the state yourself.

---

## P17 — the P&L point cap bounded heap, not the payload, and froze the curve — **RESOLVED** (2026-07-30)

**Symptom.** Asked what the logic behind the console's P&L point limit was. Reading it back:
`PnlService` stopped appending at `MAX_POINTS = 20_000` per account, set a `truncated` flag and showed
a ⚠ notice. So a demo left running long enough reached a state where **the chart stopped moving while
the market kept trading** — the console quietly became a screenshot of two hours ago.

**Root cause.** The cap was added with the continuous demo (`b3cf19c`) to stop an unbounded in-memory
list growing forever, and it does that. But it was the only bound, and it was on the wrong resource:

- `/api/pnl` serialised **every** retained point on **every** request, and the console polls once a
  second (`broker.js:29`). The response therefore grew linearly to roughly a megabyte per account and
  stayed there — about 2.4 MB/s of JSON for two accounts, and the plan to give every agent its own
  account (stories/004) would have multiplied that by five.
- Reaching the cap *stopped recording* rather than dropping the oldest, which is the one behaviour a
  live monitor must not have. Keeping the head was not arbitrary — `relative` is measured against the
  session baseline, so the first point is the anchor — but "keep the anchor" and "stop the curve" are
  not the same requirement.
- At the demo's measured ~2.4 fills/sec per account the cap landed about 2.3 hours in: far enough away
  to never show up in a test, close enough to hit over a lunch break.

**Impact.** Two failure modes that look nothing alike from the outside: a console that renders instantly
but shows stale history, and a broker steadily serving megabytes to a page nobody is reading closely.
Neither logs anything.

**Resolution.** A rolling window (FxcBroker/docs/stories/003) with three separate bounds:
`pnl.windowMs` (15 min) for what the chart means, `pnl.maxPointsPerAccount` (600 ≈ the chart's width in
pixels) for what a poll costs, and `pnl.plotAccounts` (8 = the palette size) for how many accounts carry
a curve. Eviction happens on read as well as on write, so an idle account ages out too. `truncated` is
gone; `windowMs` and a `dropped` count take its place, and the axis says "last 15 min" instead of a ⚠.

**Lesson.** A limit is only as good as the resource it is on. This one was chosen against heap — the
one resource that was never scarce — while the two things that actually degraded (payload size and
whether the chart was still true) had no bound at all. When a cap is added, write down which resource
it protects; if the answer is not the one that fails first, it is decoration.

---

## P18 — client-order-id collisions created shares and destroyed cash — **RESOLVED** (2026-07-30)

**Symptom.** Every account in a running demo was losing money — 260 of 260, including accounts with
barely any trades. Most of that is explained by mark-to-market on a falling market in a long-only
system (see below), but reading the actual positions turned up something that is not:

```
total cash    259,981,548.69   expected 260,000,000   delta   -18,451.31
total shares         260,509   expected     260,000   delta        +509
```

509 shares existed that nobody seeded, and $18,451 of cash had vanished — 509 × ~$36 ≈ the missing
cash. **The system was minting stock and burning money.**

**Root cause.** Three defects composing:

1. **`InvestorAgent` builds client order ids as `"INV-" + counter`, and the counter restarts at zero
   in every JVM.** Both demo agents use the prefix `INV`, so both emit `INV-1`, `INV-2`, … The Locust
   harness had this right (`LOC-<index>-<runtag>-<n>`); the Java agents did not.
2. **`OmsService` keyed its order map on that id and let a repeat overwrite the entry** — the exact
   footgun the harness's own comment warns about ("the broker keys its order map on TRNUID, so a
   duplicate silently overwrites prior order state"). Worse, the reject path *also* wrote to the map
   and to `CLIENT_ORDER`, so even rejecting a duplicate destroyed the original.
3. **`onExecutionReport` applied `order.side()` from that map and never looked at the side the
   exchange reported** (`BrokerFixClient` did not even read tag 54), and had no idempotency on
   `execId`. So when agent A's `INV-71` BUY and agent B's `INV-71` SELL both existed at the exchange,
   both fills resolved to whichever order won the map and were applied with *that* order's side — one
   side twice, the other never.

The evidence: 53 exchange trades with `buy_order_id = sell_order_id` (two different orders wearing one
id, crossing each other) and **152 client order ids carrying executions on both sides**. The archive
hid it: `EXECUTION` MERGEs on `exec_id` and `CLIENT_ORDER` on `client_order_id`, so the tables
deduplicated what the balances had already double-counted.

**Impact.** Silent, and in the one place a trading system must not be wrong. It also biases the
market: minted shares are supply that nobody paid for. At ~$18k against ~$1.59M of aggregate mark-to-
market losses it was not *the* reason the demo looked bearish, but it was real money moving to nobody.

**Resolution.** Conservation is now enforced at three points, with `OrderIdentityTest` asserting each:
a reused client order id is **rejected without touching the stored order** (what a real OMS does with
a duplicate ClOrdID); an `execId` already applied is ignored, so a FIX resend cannot move balances
twice; and a report whose side disagrees with the order that id resolves to is refused rather than
guessed at, with counters (`duplicateReportCount`, `mismatchedReportCount`) so it is visible. Agent
ids are now qualified with the account and a start-time tag, so they are unique across agents *and*
across restarts.

**Lesson.** The dedupe in the persistence layer was not a safety net — it was camouflage. `MERGE` on a
natural key makes duplicate *writes* idempotent while doing nothing for the duplicate *effects*
already applied, and it removes the evidence that would have shown up as duplicate rows. When an
identifier is a primary key for state that moves money, its uniqueness has to be enforced where the
state moves, not where it is written.

---

## P19 — seeding investors with shares inflated the float, and a long-only market can only fall — **RESOLVED** (2026-07-30)

**Symptom.** A demo run with 260 accounts showed **every single account losing money** — 260 of 260,
including accounts with barely any trades. The price fell 42.10 → 32.23 (−23%) monotonically, the
order book had **no bids at all** (340 resting offers, `bestBid: null`), and adding more investors with
seed capital made it worse rather than better.

**Root cause.** Three properties of the demo compounding, none of them a calculation error:

1. **Every opened account was seeded with 1,000 shares as well as $1M.** 260 accounts injected
   **260,000 shares against an original float of 2,000** — a 130× supply increase. Adding an "investor
   with capital" was adding stock.
2. **Nobody can be short.** `AccountService.check` refuses a sell beyond holdings ("no shorting"), so
   every participant is structurally long. In a long-only market with a falling price, *100% losers is
   the only possible outcome* — there is no position that profits from a decline. That is why the
   result looked impossible for a closed system.
3. **Mark-to-market is not zero-sum.** Equity is `cash + shares × P` with one `P` applied to every
   share in existence. Cash and shares are conserved; their *valuation* is not. A 9.87 fall across
   260,000 shares removed ~$2.5M of marked value that never existed as cash and that nobody received.

The microstructure supplied the direction: the engine executes at the **resting** order's price, so a
buyer who wants to pay more simply lifts an offer and can never print a higher price, while a seller
can always undercut and print a lower one. With every strategy anchoring its quotes to the last sale,
the print ratchets down and drags the quotes with it.

**Impact.** The demo's headline screen told a story that was arithmetically correct and completely
misleading: it looked like every strategy was bad, when what it showed was a float being inflated
faster than capital could absorb it.

**Resolution.** Opened accounts are funded **cash only** (`account.open.seedShares = 0`). The tradable
float is what the seeded dev accounts hold and does not move when investors are added; investors
arrive with money and have to bid for it. `AccountOpeningTest.openingAccountsDoesNotChangeTheFloat`
pins the invariant. Share seeding stays available (`account.open.seedShares`) for anyone who wants an
agent to start long.

**Lesson.** "Injecting liquidity" is not one thing. Cash and stock are both liquidity and they push the
price in opposite directions; a seeding policy that hands out both is choosing a price direction
whether or not anyone intended to. And in a market where nobody can be short, *everyone losing* is not
evidence of a bug — it is what a falling price means when there is no other side to be on.
