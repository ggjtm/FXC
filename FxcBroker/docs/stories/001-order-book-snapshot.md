# Investor-requested order-book snapshot

Status: proposed
Relates to: root DESIGN §4.1 (exchange market data) / §4.2 (broker); FxcInvestor I5 &
[stories/002-booker](../../../FxcInvestor/docs/stories/002-booker-agent.md)

## Summary

Let an investor **request a market order-book snapshot** for an instrument from the broker over OFX.
The broker sources the book from **FxcExchange's FIX market-data service** (it already connects to
the exchange over FIX) and returns the aggregated depth to the investor.

This unblocks the `booker`/`bookfish` agents, which need live order-book depth that the plain
OFX/XMPP investor cannot otherwise see (FxcInvestor **I5**).

## Motivation

- `booker` samples a price target from a **quantity-weighted order-book histogram**; it needs
  `(price → resting quantity)` per side. That data lives at the exchange, not the broker's books.
- The broker is the investor's only OFX counterparty and already holds a FIX session to the
  exchange — so the broker is the natural place to relay a book snapshot.

## Flow

1. **Investor → Broker (OFX):** a market-data request for a symbol (depth N), carried in a **custom
   OFX message set** (OFX has no native book/quote messages — mirror the order-entry approach in
   DESIGN §6.4, e.g. `@Aggregate("FXCMDMSGSRQV1")` with a `FXCMDRQ` carrying `SECID` + depth).
2. **Broker → Exchange (FIX):** the broker issues a `MarketDataRequest(35=V)` (snapshot type, or
   reuses a standing `SNAPSHOT_PLUS_UPDATES` subscription it maintains + caches) for the symbol; the
   exchange replies with `MarketDataSnapshotFullRefresh(35=W)` (bids/asks: `MDEntryType`,
   `MDEntryPx`, `MDEntrySize`), plus the last trade.
3. **Broker → Investor (OFX):** the broker returns the aggregated book — a list of
   `(side, price, size)` levels up to the requested depth, plus last-sale — in the mirrored
   response message set (`FXCMDMSGSRSV1` / `FXCMDRS`).

## Approach notes

- **Standing subscription + cache (preferred):** the broker maintains a `SNAPSHOT_PLUS_UPDATES`
  subscription per traded symbol and serves the latest cached top-N book on demand — low latency,
  no per-request FIX round-trip. Alternative: on-demand snapshot request per investor call (simpler,
  higher latency). Decide at implementation.
- Reuse the shared OFX plumbing in `fxc-common` (`OfxCodec`, custom-aggregate package
  `com.webcohesion.ofx4j.domain.data.fxc`) so broker and investor round-trip the new message set,
  exactly like the order-entry set.
- SECID convention matches statements/orders (equity ticker; synthetic `FX:PAIR`), so books,
  positions, and orders line up.

## Acceptance criteria

- An investor OFX request for symbol `S` (depth `N`) returns the current top-`N` bid and ask levels
  (price + aggregated size) and the last-sale price, sourced from the exchange FIX feed.
- Depth honored (≤ N levels per side); empty book returns an empty level list (not an error).
- Snapshot reflects the exchange book within the freshness bound of the caching strategy chosen.
- A `booker` agent, fed this snapshot into its `MarketView`, produces book-weighted price targets
  (FxcInvestor story 002) end-to-end.

## Out of scope / later

- Streaming/incremental book updates to the investor (this story is request/response snapshots).
- Order-book access control / entitlements (dev uses open access).
- Consolidated/multi-venue books (single exchange for now).
