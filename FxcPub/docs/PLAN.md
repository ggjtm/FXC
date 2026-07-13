# FxcPub — Component Plan

Scoped plan for the XMPP-native publication component. Companion to the root
[docs/PLAN.md](../../docs/PLAN.md) §Phase 3 and [docs/DESIGN.md](../../docs/DESIGN.md) §4.3.

**Design principle:** stock Tigase runs **100% unmodified** as an external service; FxcPub's own
code is a set of **XMPP clients** (Smack). Mastodon REST is deferred to the Phase-7 gateway addon.

## Status: **in progress** — AGPLv3 accepted via the unmodified-server strategy (root PROBLEMS.md P2)

The hold is lifted: Tigase runs 100% unmodified (server-side config only) with custom features on
the client side (Smack). Phase 3 is underway.

## Plan (root Phase 3)

1. [x] **Stand up stock Tigase** — DONE. `docker/tigase/` builds the unmodified dist on JDK 17;
   `tigase-init` loads the schema (with the MariaDB FK fix) and provisions trusted service accounts
   (`admin`/`broker`/`pub-service`/`investor`) from config. Proven by `PubSubRoundTripIT` (Smack
   publish→subscribe round-trip). Integration seam decided: **trusted Smack client** connections.
   See PROBLEMS.md "Phase-3 spike outcome".
2. [~] **FxcPub XMPP-client services** (Smack): `XmppConnectionFactory` built and proven.
   **Remaining:** a `PubSubClient` publish/subscribe wrapper and the GridGain-fed read models.
3. [ ] **GridGain node + hot tables** `PUB_ACCOUNT`, `STATUS`, `FOLLOW` as projections fed by pubsub
   events; `TimelineService` fan-out. (Reuse the `GridNode` pattern from FxcExchange.)
4. [ ] **FIX drop-copy acceptor** (QuickFIX/J): receive `ExecutionReport`s from brokers, render as
   statuses, publish via `FixGatewayService` acting as an XMPP client. **Not gated on Tigase** for
   the acceptor + rendering; publishing is.
5. [ ] **ArchiveService** (root Phase 5): archive aged statuses to `fxc_pub` MariaDB; deep-history
   reads fall back to MariaDB.

## Exit criteria (root Phase 3)

A fill on FxcExchange appears as a status on the broker's feed, readable via an XMPP (Smack)
subscription to the pubsub node.

## Deferred (NOT this component)

- **Mastodon-compatible REST API** → Phase 7 gateway addon (separate service, also an XMPP client of
  stock Tigase). The Javalin dependency belongs there, not here.

## Unblock checklist

- [ ] AGPLv3 acceptance for running the Tigase image (root PROBLEMS.md P2).
- [ ] JDK 21 confirmation via the spike (low risk; vendor Docker image builds on 21).
- [ ] Correct image name is `tigase/tigase-xmpp-server:8.4.1` (docs corrected this session).
