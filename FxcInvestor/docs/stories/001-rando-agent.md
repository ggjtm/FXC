# rando — uniform-random agent

Status: proposed
Relates to: PLAN item 5 (Strategy SPI + agents); root DESIGN §4.4

## Summary

`rando` is the simplest autonomous investor agent. On each decision tick it:

1. Picks a **side** at random (buy or sell), with equal probability.
2. Picks a **quantity** uniformly at random in **1–10 shares** (integer).
3. Picks a **limit price target** uniformly at random within **±1% (absolute value)** of the
   **most recent last-sale price** for the instrument, i.e. a price drawn uniformly from
   `[last * 0.99, last * 1.01]`, then snapped to the instrument tick size.
4. Submits the order to the broker over OFX (custom order-entry message set).

It is deterministic-testable by injecting the RNG seed.

## Data needed

- **Most recent last-sale price** per instrument. Source: the FxcPub feed (fill statuses carry
  `@ <price>`) and/or the OFX statement. The agent maintains a `lastSale` map in its `MarketView`.

## Acceptance criteria

- Given a last-sale of `P`, every generated target price lies in `[0.99P, 1.01P]` and is a multiple
  of the instrument tick size.
- Generated quantities are integers in `[1, 10]`.
- Buy/sell selection is ~50/50 over many ticks.
- With a fixed seed, the sequence of `(side, qty, price)` decisions is reproducible.
- Wired through the single-instance runner ([004](004-single-instance-runner.md)), `agent on`
  trades autonomously and its fills appear on the FxcPub feed (root Phase 3 exit path).

## Notes

- This is the reference/demo strategy; `booker` ([002](002-booker-agent.md)) and `bookfish`
  ([003](003-bookfish-agent.md)) refine the **price-target distribution** while keeping the same
  side/quantity behavior.
- No market microstructure awareness — intentionally naive, useful as a liquidity/noise trader in
  simulations ([005](005-gatling-multi-agent-runner.md)).
