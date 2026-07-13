# FxcPub — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## P-1 — Tigase AGPLv3 acceptance — **OPEN (HOLD)**

Root PROBLEMS.md P2. Running the stock Tigase image is using AGPLv3 software. Jeremy chose to
**HOLD**; the Phase-0 spike is un-run and this component is blocked. The unmodified-external design
isolates the copyleft, but the acceptance is a business decision to confirm before proceeding.

## P-2 — Tigase deployment model — **RESOLVED**

Root PROBLEMS.md P2. Tigase runs stock, standalone, external (docker-compose), never embedded — no
verified embed-as-library API. FxcPub interacts strictly as an XMPP client (Smack).

## P-3 — Docker image name — **RESOLVED**

The official image is **`tigase/tigase-xmpp-server`** (tag `8.4.1` confirmed), not
`tigase/tigase-server` (that is only the GitHub source-repo name). Corrected across the docs this
session; `docker-compose.yml` uses the right name.

## P-4 — XMPP-client integration seam — **OPEN**

Decide how FxcPub's XMPP-client services publish into / read from Tigase PubSub: a trusted/admin
Smack client connection or ad-hoc commands (root PROBLEMS.md P2). Resolve during the spike (item 2).

## P-5 — JDK 21 support for Tigase — **OPEN (low)**

Documented minimum is JDK 17; the vendor Docker image builds on 21, so 21 works in practice, but
there is no explicit "certified on 21" statement. Confirm in the spike.

## P-6 — GridGain does not replace Tigase's repository — **NOTE**

Tigase persists its own XMPP state (users, auth, offline, pubsub) via its JDBC repository → MariaDB
(`tigasedb`). GridGain holds FXC application-domain hot state (timeline/follow projections) only.
