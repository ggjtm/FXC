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
- **JDK 21 support — mostly confirmed, verify in spike.** Documented minimum is **JDK 17**; the
  official Docker image builds on **21** (so 21 works in practice), but there is no explicit
  "certified on 21" statement. Low risk given we run the vendor Docker image.
- **License — AGPLv3. DECISION: HOLD (2026-07-13).** Tigase XMPP Server is **AGPLv3** (separate
  commercial license available). Running it as a separate, **unmodified** process (the P1 decision)
  means its copyleft does not automatically extend to FxcPub — but this is a legal judgment to
  confirm, and contrasts with the permissive licenses elsewhere (QuickFIX/J BSD-style, OFX4J
  Apache-2.0). Jeremy chose to **hold**: the Tigase image is not to be pulled/run yet, so the
  Phase-0 spike stays un-run and FxcPub (Phase 3) remains gated. Phases 1–2 proceed regardless.
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

### Phase-0 spike status (2026-07-13)

Progress so far, and what remains before the gate is cleared:

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

## P4 — GridGain 8 CE is not on Maven Central — **MITIGATED**

See [DESIGN.md §6.7](DESIGN.md). Build must add the GridGain Nexus repository, or use the
API-compatible Apache Ignite 2.x fallback (identical `org.apache.ignite.*` namespace). Embedded
GridGain/Ignite also requires a specific `--add-opens` flag set on JDK 21
(`.reference/gridgain/README.md`).
