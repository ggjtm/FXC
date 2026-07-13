# FxcInvestor — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## I1 — Timeline/feed features blocked by Tigase hold — **OPEN**

The XMPP client (home-timeline ingestion + posting) and the `feed`/`post` CLI verbs depend on
FxcPub/Tigase, which is on hold pending AGPLv3 acceptance (root PROBLEMS.md P2). The OFX-driven
trading path (signon, statements, order entry, strategy loop) is independent — build and test that
against FxcBroker first.

## I2 — Custom OFX order-entry message set must match FxcBroker — **OPEN**

Order submission uses `FXC.ORDERMSGSRQV1`, whose shape is finalized in FxcBroker Phase 2 (root
PROBLEMS.md P3/P4). Keep the shared constants (`com.fxc.common.ofx.OfxMessageSets`) as the single
source of truth; do not diverge.

## I3 — Static dev credentials — **OPEN (low)**

OFX signon (and the deferred Mastodon OAuth) use static dev credentials initially (root DESIGN §6.7).

## I4 — Strategy determinism for tests — **MITIGATED (by design)**

`Strategy.evaluate(MarketView, PortfolioView, FeedView)` is designed to be pure/deterministic so the
decision loop is unit-testable without live services. Keep side effects (order submission) out of
the strategy itself.
