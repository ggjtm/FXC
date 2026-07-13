# FxcInvestor — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## I1 — Timeline/feed features blocked by Tigase hold — **OPEN**

The XMPP client (home-timeline ingestion + posting) and the `feed`/`post` CLI verbs depend on
FxcPub/Tigase, which is on hold pending AGPLv3 acceptance (root PROBLEMS.md P2). The OFX-driven
trading path (signon, statements, order entry, strategy loop) is independent — build and test that
against FxcBroker first.

## I2 — Custom OFX order-entry message set must match FxcBroker — **RESOLVED (Phase 4)**

The custom order-entry aggregates (`FXCORDMSGSRQV1`/`RSV1`, under
`com.webcohesion.ofx4j.domain.data.fxc`) and the OFX codec (`com.fxc.common.ofx.OfxCodec`) now live
in **fxc-common** (moved from FxcBroker), so broker (server) and investor (client) round-trip the
exact same classes — no divergence possible. `fxc-common` gained an `api` dependency on ofx4j.

## I5 — booker/bookfish need order-book market data — **RESOLVED**

`rando` needs only last-sale (from the feed). `bookfish` (stories/003) is self-contained — its
traded-volume histogram is built from the FxcPub feed. `booker` (stories/002) needs live
**order-book depth**, now supplied by the **broker order-book-snapshot relay** (FxcBroker/docs/
stories/001): `OfxBrokerClient.requestBook` fetches depth over OFX and the runner feeds it into
`MarketView.setBook` each tick. Verified end-to-end by `BookRelayIntegrationTest`.

## I3 — Static dev credentials — **OPEN (low)**

OFX signon (and the deferred Mastodon OAuth) use static dev credentials initially (root DESIGN §6.7).

## I4 — Strategy determinism for tests — **MITIGATED (by design)**

`Strategy.evaluate(MarketView, PortfolioView, FeedView)` is designed to be pure/deterministic so the
decision loop is unit-testable without live services. Keep side effects (order submission) out of
the strategy itself.
