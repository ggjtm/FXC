# bookfish — traded-volume-weighted agent

Status: implemented (traded-volume histogram fed from the XMPP feed; 0.5σ filter)
Relates to: PLAN item 5 (Strategy SPI + agents); [002](002-booker-agent.md)

## Summary

`bookfish` trades like [`booker`](002-booker-agent.md) but samples its **limit price target** from a
histogram of **volume actually traded at each price** (executions), rather than resting book depth:

1. Maintain a **traded-volume histogram**: for each price, accumulate the executed quantity seen in
   trades (bin = price, weight = cumulative traded quantity at that price).
2. Draw a price target at random from that histogram (prices where more volume has traded are more
   likely).
3. **Filter**: reject/clamp draws more than **0.5 standard deviation** (of the traded-volume price
   distribution) from the **most recent last-sale price** — a tighter band than `booker`.
4. Snap to tick size and submit over OFX.

## Data needed

- **Most recent last-sale price** (as in `rando`).
- **Traded-volume-by-price histogram**, accumulated from executions. Unlike `booker`'s live book,
  this is derivable from the **FxcPub feed** over time: every fill status carries price + quantity,
  so the agent can build the histogram purely from the XMPP feed it already consumes (no extra
  market-data dependency). The runner may also seed it from FxcExchange trade data for faster warmup.

## Acceptance criteria

- The price-target distribution matches the traded-volume histogram (goodness-of-fit over many
  draws), restricted to within 0.5σ of last sale.
- No target is emitted further than 0.5σ from last sale.
- Empty/degenerate histogram falls back to `rando`'s ±1% behavior.
- Deterministic with a fixed RNG seed and a fixed histogram.

## Notes

- Reuses the `PriceTargetSampler` seam from `booker`; only the histogram source (traded volume vs
  resting book) and the σ multiplier (0.5 vs 1.0) differ.
- Because its signal comes from the feed it already ingests, `bookfish` is the most "self-contained"
  of the three for a pure OFX/XMPP investor.
