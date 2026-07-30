# bookfish — traded-volume-weighted agent

Status: implemented (traded-volume histogram fed from the XMPP feed; 0.5σ filter; **patient** since
2026-07-29 — see [Patience](#patience-waiting-for-an-advantage))
Relates to: PLAN item 5 (Strategy SPI + agents); [002](002-booker-agent.md);
[007](007-investor-mix-control.md) (the harness that made the patience gap visible)

## Summary

`bookfish` trades like [`booker`](002-booker-agent.md) but samples its **limit price target** from a
histogram of **volume actually traded at each price** (executions), rather than resting book depth:

1. Maintain a **traded-volume histogram**: for each price, accumulate the executed quantity seen in
   trades (bin = price, weight = cumulative traded quantity at that price).
2. Draw a price target at random from that histogram (prices where more volume has traded are more
   likely).
3. **Filter**: reject/clamp draws more than **0.5 standard deviation** (of the traded-volume price
   distribution) from the **most recent last-sale price** — a tighter band than `booker`.
4. **Wait unless the draw is advantageous** (below).
5. Snap to tick size and submit over OFX.

## Data needed

- **Most recent last-sale price** (as in `rando`).
- **Traded-volume-by-price histogram**, accumulated from executions. Unlike `booker`'s live book,
  this is derivable from the **FxcPub feed** over time: every fill status carries price + quantity,
  so the agent can build the histogram purely from the XMPP feed it already consumes (no extra
  market-data dependency). The runner may also seed it from FxcExchange trade data for faster warmup.

## Patience: waiting for an advantage

**The problem.** Step 3's filter is a *band*, not a veto: when the histogram is thin, sampling falls
back to a uniform ±1% draw around the last sale. That fallback is `rando`'s behaviour wearing
`bookfish`'s name, and nothing distinguishes the two from the outside — a decision log or a stats table
shows orders either way. A strategy whose premise is "trade where volume trades" should **decline** when
it cannot see where volume traded.

**The gates**, applied after the sampler has drawn (both, in order):

1. **Readiness.** The traded-volume histogram must support a distribution at all — two or more
   positive-weight bins with non-zero spread — *and* carry at least **50** units of observed volume.
   Otherwise: no order, reason `not-ready`.
2. **Edge.** Fair value is the **volume-weighted mean price** of that histogram: the same centre the
   sampler draws around. The drawn target must sit at least **1 tick** on the *favourable* side of fair
   value for the side drawn — below it to buy, above it to sell. Otherwise: no order, reason `no-edge`.

Both thresholds are configurable (`--bookfish-min-volume`, `--bookfish-min-edge-ticks`;
`minVolume`/`minEdgeTicks` in Java). `minEdgeTicks = 0` disables the edge gate, which restores exactly
the pre-patience behaviour and is how the tests prove the gate is what changed.

**Consequences, all deliberate:**

- **The draw happens first, always.** Side, quantity and price are drawn before either gate is
  consulted, so declining consumes exactly the same random numbers as trading. Patience cannot shift a
  seeded sequence.
- **Roughly half of all ticks decline** in a symmetric market: the coin-flipped side agrees with the
  drawn price's direction about half the time. That is the patience, and it is visible as
  `BOOKFISH skipped:no-edge`.
- **A quiet market can decline indefinitely, correctly.** If all the volume sits at the last sale and
  0.5σ is tighter than the gap to the neighbouring prices, the only price `bookfish` can draw *is* fair
  value — no advantage exists, so it waits. It resumes as soon as the market moves away from where
  volume traded, which is the mean-reverting behaviour the histogram implies.
- **It is stateless** — no cooldown, no memory. This is what keeps it from starving the liquidity
  policy that wraps it: `LiquidityAwareStrategy` declines whenever its delegate declines, so an
  abstention delays a forced liquidity sell by one tick at most, never prevents it.

**Wrapping order is `LiquidityAwareStrategy(PatientStrategy(bookfish))`** — patience inside, liquidity
outside, so the policy can size the intent patience approved.

## Acceptance criteria

- The price-target distribution matches the traded-volume histogram (goodness-of-fit over many
  draws), restricted to within 0.5σ of last sale.
- No target is emitted further than 0.5σ from last sale.
- Empty/degenerate histogram falls back to `rando`'s ±1% behavior **in the sampler**, and the patient
  strategy turns that case into an abstention rather than an order.
- Every submitted order is at least `minEdgeTicks` on the favourable side of fair value.
- An abstention consumes the same RNG draws as an order.
- Deterministic with a fixed RNG seed and a fixed histogram.

## Notes

- Reuses the `PriceTargetSampler` seam from `booker`; only the histogram source (traded volume vs
  resting book) and the σ multiplier (0.5 vs 1.0) differ.
- Because its signal comes from the feed it already ingests, `bookfish` is the most "self-contained"
  of the three for a pure OFX/XMPP investor. The Locust harness has no XMPP client, so it substitutes
  the exchange's public volume-by-price feed ([007](007-investor-mix-control.md)); with neither, the
  histogram is one process's own fills and patience reports `not-ready` until enough accumulate.
- **Implemented twice**: `com.fxc.investor.strategy.PatientStrategy` and `fxc_loadgen.patience`, with
  matching tests, for the reason DESIGN §6.5 gives for the liquidity policy — a strategy must not mean
  two different things in two languages. The one asymmetry is reporting, not behaviour: Java records
  `SKIPPED` in the decision log as before (the reason is available via `lastReason()` but unused), while
  the harness breaks the reason out into its own stats row because an operator has to tell a patient
  strategy from a broken one at a glance.
