# FxcInvestor — Problems & Risk Log

Component-scoped risks. Project-wide risks live in the root [docs/PROBLEMS.md](../../docs/PROBLEMS.md).
Status per entry: **OPEN**, **RESOLVED**, or **MITIGATED**.

---

## I1 — Timeline/feed features blocked by Tigase hold — **RESOLVED** (2026-07-13)

The XMPP client (home-timeline ingestion + posting) and the `feed`/`post` CLI verbs depended on
FxcPub/Tigase, which was on hold pending AGPLv3 acceptance (root PROBLEMS.md P2). The OFX-driven
trading path (signon, statements, order entry, strategy loop) is independent, and was built and
tested against FxcBroker first.

**Resolution.** The AGPLv3 hold was accepted on 2026-07-13: stock, unmodified Tigase 8.4.1 runs in its
own container, `FeedClient` and the `feed`/`post` verbs are wired, and `scripts/demo.sh` starts the
whole loop. `EndToEndDemoIT` asserts the fill → FxcPub → investor-feed leg deterministically. This
entry stayed OPEN for two weeks after the fact, which is how the stale-status sweep in
FxcInvestor/docs/stories/007 found it.

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

OFX signon (and the deferred Mastodon OAuth) use static dev credentials initially (root DESIGN §7.7).

## I4 — Strategy determinism for tests — **MITIGATED (by design)**

`Strategy.evaluate(MarketView, PortfolioView, FeedView)` is designed to be pure/deterministic so the
decision loop is unit-testable without live services. Keep side effects (order submission) out of
the strategy itself.
