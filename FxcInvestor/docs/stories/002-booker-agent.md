# booker — order-book-weighted agent

Status: implemented (sampler + σ filter; live book depth supplied via the broker relay —
FxcBroker/docs/stories/001)
Relates to: PLAN item 5 (Strategy SPI + agents); [001](001-rando-agent.md)

## Summary

`booker` trades like [`rando`](001-rando-agent.md) for **side** (random buy/sell) and **quantity**
(1–10 shares), but chooses its **limit price target** from the shape of the live order book rather
than uniformly:

1. Build a **weighted histogram** of the current order book: each price level is a bin, weighted by
   the resting quantity at that level (both bid and ask sides).
2. Draw a price target at random from that histogram (levels with more resting quantity are more
   likely to be chosen).
3. **Filter**: reject/clamp any draw more than **one standard deviation** (of the book's
   price distribution, quantity-weighted) away from the **most recent last-sale price**. Redraw or
   clamp to the nearest in-band price.
4. Snap to tick size and submit over OFX.

## Data needed

- **Most recent last-sale price** (as in `rando`).
- **Current order-book depth** (price → resting quantity, both sides). This is exchange market data
  that the plain OFX/XMPP investor does not see. Source options (decide during implementation):
  - the simulation/runner supplies a market-data feed (e.g. a FIX `MarketDataRequest` subscription
    to FxcExchange) into the agent's `MarketView`; **or**
  - a future broker-side OFX quote/book extension exposes top-N depth.
  For the Gatling simulation runner ([005](005-gatling-multi-agent-runner.md)), the harness provides
  the book snapshot.

## Acceptance criteria

- The price-target distribution matches the quantity-weighted book histogram (goodness-of-fit over
  many draws), restricted to within 1σ of last sale.
- No target is emitted further than 1σ from last sale.
- Degenerate book (empty/one level) falls back to `rando`'s ±1% behavior.
- Deterministic with a fixed RNG seed and a fixed book snapshot.

## Notes

- Standard deviation is computed over the quantity-weighted price distribution of the book.
- Shares the side/quantity logic with `rando`; only the price-target sampler differs — implement as
  a pluggable `PriceTargetSampler` so `rando`/`booker`/`bookfish` reuse the agent shell.
