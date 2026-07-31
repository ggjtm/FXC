# Rolling-window P&L on the broker console
Status: done
Relates to: root DESIGN §6.3 / [002](002-broker-monitor-ui.md) / root PROBLEMS.md P17

## Summary

The console's per-account P&L curve is now a **rolling 15-minute window** instead of a fixed-size log
that stopped recording. Three bounds replace the single point ceiling, and each one answers a
different question: how much history the chart *means*, what a response *costs*, and how many accounts
carry a curve at all.

## Motivation

`PnlService` sampled one point per fill into an in-memory list per account and stopped appending at
`MAX_POINTS = 20_000`, reporting `truncated` and showing a ⚠ notice. Two things were wrong with that,
and the second is the one that mattered:

1. **The curve froze.** Reaching the cap meant the chart stopped moving while the demo kept trading —
   the console silently became a screenshot. At the demo's observed ~2.4 fills/sec per account that
   took about two hours; a demo left running over lunch got there.
2. **Nothing bounded the payload.** `/api/pnl` serialised *every* point on *every* request and the
   console polls once a second, so the response grew linearly to ~1 MB per account and stayed there.
   The cap bounded heap, which was never the scarce resource.

Adding an account per agent ([004](004-account-opening.md)) made both worse: ten accounts, ten curves,
ten growing payloads.

## What it does

| Bound | Config | Default | Job |
|---|---|---|---|
| Time window | `pnl.windowMs` | 900 000 (15 min) | what the chart means |
| Points per account in a response | `pnl.maxPointsPerAccount` | 600 | what a poll costs |
| Accounts carrying a curve | `pnl.groupSize` | 5 | per group: best P&L, most active, worst P&L |
| Accounts hidden entirely | `pnl.internalAccountsBelow` | 100 | the broker's own — issuer (0), market makers (1, 2) |
| Retained points backstop | `pnl.maxRetainedPoints` | 5 000 | a burst inside the window |

- **Eviction is normal, not an error.** Points older than the window are dropped on write *and* on
  read — on read as well because an idle account's curve should age out even though no fill is
  arriving to trigger it. `truncated` is gone; `windowMs` and a cumulative `dropped` take its place,
  and the console's ⚠ notice becomes an axis subtitle ("cumulative trades · last 15 min").
- **y is unchanged.** `relative` is still equity now minus equity at session start, so accounts stay
  comparable on one scale and the level survives eviction. The alternative — re-baselining to the
  window — would have made every line start at zero and thrown away the session's story.
- **Downsampling keeps the endpoints.** Stride sampling to the budget, always including the first and
  last point: those are the two values a reader actually checks against the axis, and at 600 points
  across a ~600-pixel chart the thinning is invisible.
- **Totals never change.** They are computed live from positions, not summed from the curve, so
  evicting points cannot alter a number in the table. There is a test that says exactly this.
- **The broker's own accounts never appear.** Accounts numbered below `pnl.internalAccountsBelow` — the
  issuer and the market makers — are filtered out before the groups are picked, so they can neither draw
  a curve nor take a slot. They hold the entire float, and mark-to-market on it runs three orders of
  magnitude larger than any customer's P&L.
- **The busiest accounts get the curve**, ties broken on account number so the selection is stable
  between polls — a curve that appeared and vanished as two accounts swapped places would read as a
  bug in the chart. The rest arrive with totals and `points: []`, and the legend says "+N more in the
  table".

## Acceptance criteria

- [x] The curve keeps moving indefinitely; nothing freezes.
- [x] `/api/pnl` is bounded in size regardless of run length or account count.
- [x] Totals and trade counts are unaffected by eviction.
- [x] The chart says which window it is showing.
- [x] An account the chart does not plot is still fully reported in the table, and counted in the
      legend rather than silently missing.

## Verification

`PnlServiceTest` (16, of which 8 new): eviction by window with a hand-driven clock, eviction on read
for an idle account, totals surviving eviction, the burst backstop, top-N selection, the response
budget, and downsampling (budget respected, endpoints exact, order preserved). `BrokerWebApiTest`
asserts `windowMs`/`dropped`/`plotted` on the wire.

Live (2026-07-30, 20 accounts, 10 trading agents): the window holds — the oldest retained point sits at
**896 s against the 900 s window** and `dropped` climbs from there, while the curves keep advancing
(930 trades per account and counting). The payload **plateaus at ~411 KB** instead of growing without
limit: 8 plotted accounts × the 600-point budget × ~85 bytes a point. Bounded, but bigger than the
~200 KB the plan guessed — halve it with `pnl.maxPointsPerAccount = 300`, which is still two pixels a
point on a 600-pixel chart, or with `pnl.plotAccounts`.

## Out of scope / later

Persisting the curve so it survives a broker restart (the columns exist in `EXECUTION_ARCHIVE`; the
read path does not — still listed in [002](002-broker-monitor-ui.md)). Peak-preserving downsampling
(LTTB): stride sampling is indistinguishable at this density and much easier to reason about. A
per-account window override.
