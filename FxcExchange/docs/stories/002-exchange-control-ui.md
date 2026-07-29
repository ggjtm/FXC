# Exchange controller/monitor console
Status: done
Relates to: root DESIGN §6.2 / PLAN "Additional stories" / story 001 (exchange feeds)

## Summary

A dark-themed static web console at `http://localhost:8090/` with a mouseover dropdown of operator
controls — stop/start trading (market-wide or for the selected symbol) and clear the order book — over
a D3+SVG main pane showing 1-minute candles for a selectable security, a translucent volume underlay
in the bottom 20% of the plot area, and story 001's right-side volume-by-price histogram.

## Motivation

Root DESIGN §6 asks for controls the exchange had no backend for. Before this story:

- there was **no market-state concept anywhere** — `MatchingEngine.submit` accepted orders
  unconditionally and every FIX session runs 24h (`StartTime=EndTime=00:00:00`), so "starting and
  stopping trading sessions" had nothing to switch;
- there was **no mass cancel** — only single-order `OrderBook.cancel(orderId)`;
- the HTTP surface was **GET-only** with no static-asset handler.

## Flow

1. The page loads `/api/config` (WebSocket port, whether controls are live), then `/api/symbols`.
2. `/api/candles` fills the chart; leaving the interval end empty opens the live ticker WebSocket and
   folds each one-second tick window into the current candle.
3. `/api/status` is polled once a second, independently of the chart, and drives the status pill and
   the enabled/disabled state of the menu items.
4. A control item POSTs to `/api/session/halt|open` or `/api/book/clear` (query parameters, empty
   body) and then refreshes status.

## As built

**Domain (pure, no infra):**

- `book/TradingSession` — a market-wide state plus a set of individually halted symbols. The effective
  state is the **safer of the two**: a symbol trades only when the market is open *and* that symbol is
  not individually halted. Deliberately a conjunction, so a market-wide halt cannot be defeated by a
  stale per-symbol setting and resuming the market does not silently resume a symbol an operator
  halted on its own.
- `MatchingEngine.submit` checks the halt **first**, before the instrument lookup and validation, so a
  halted market always rejects for the halt and never masks it behind an incidental second fault.
  Cancels stay permitted during a halt — a halt must not trap resting orders.
- `OrderBook.cancelAll` / `MatchingEngine.clearBook` / `clearAll`, returning the cancelled orders.
  Partial fills keep their `cumQty`; only the open remainder is cancelled.

**Service and transport:**

- `control/CancelReporter` (implemented by `ExchangeApplication`) and `control/ExchangeControlService`.
- `FeedHttpServer` gains `GET /api/status`, `GET /api/book`, `POST /api/session/halt|open`,
  `POST /api/book/clear`, and static serving of `/`, `/assets/*`, `/common/*`.
- `LiveFeed` gains a heartbeat and a trades/sec rate; `WebSocketFeedServer` gains a real `pong`
  and a `broadcast` that bypasses the symbol filter.
- `feed.controls.enabled` (default true) — when false the mutating contexts are never registered and
  the console hides its menu.

**UI:** `web/index.html` + `web/assets/exchange.js`, using `/common/*` from `fxc-common`.

## Approach notes

**The most important behaviour in this story is that clearing the book reports every cancellation
back to its owning broker over FIX.** Each broker's OMS tracks order state from `ExecutionReport`s, so
orders that disappeared from the exchange without a `CANCELED` report would leave every connected
broker believing it still had live orders. That is a silent state divergence no HTTP-level assertion
would catch, which is why the test drives real FIX orders and asserts the reports arrive.

**Volume layout — reconciling two specs.** DESIGN §6 asks for a transparent volume underlay in the
bottom 20% of the chart canvas; story 001 asked for a bottom volume strip *plus* a right-side
30%-transparent volume-by-price histogram; the shipped Canvas version drew a separate 28% strip. As
built: one plot area, volume bars drawn as a translucent underlay scaled into the bottom 20% of that
same area with candles painting over them (§6), **keeping** story 001's right-side histogram at 30%
opacity. The separate strip is gone.

Volume having its own vertical extent is a second scale on one plot, which is normally a chart smell.
It is contained rather than hidden: the band carries **no axis and no gridlines**, sits beneath the
price marks in the bottom fifth, and its values are readable as text in the readout and the tooltip —
nothing invites reading a volume magnitude off the price axis.

**A real bug fixed on the way.** The Canvas renderer indexed candles by array position, but
`CandleAggregator` omits empty buckets, so a quiet market's gaps were drawn as if they were adjacent
minutes. A D3 `scaleTime` axis makes the gaps truthful. Verified by replaying a real 240-bar payload
with ~45% of buckets removed and asserting every k-bucket gap maps to exactly k bar widths.

Candles carry direction by **shape as well as colour** (hollow body = up, filled = down) because the
red/green pair measures ΔE 8.2 under deuteranopia — over the target, but too narrow a margin for the
one distinction the chart most needs to convey.

## Acceptance criteria

- [x] Halting rejects new orders with a reason naming the halt; cancels still work; resuming re-accepts.
- [x] Per-symbol halt stops only that symbol; a market halt stops everything.
- [x] Clearing the book empties both sides and delivers `ExecType=CANCELED` to each owning broker.
- [x] `/api/status` reports market state, per-symbol quotes/depth, feed throughput and WS clients.
- [x] `/api/book` exposes aggregated depth (previously FIX-only).
- [x] Controls can be switched off, leaving a working read-only console and 404 on the POST paths.
- [x] Every asset the page references is served with a usable content type.

## Verification

`TradingSessionTest` (7), `MatchingEngineHaltTest` (14), `ExchangeControlApiTest` (2, real FIX client
against a live exchange), `WebSocketFeedServerTest` (4, incl. heartbeat and pong), `FeedUiServingTest`
(page + all assets + traversal rejection).

Chart geometry was checked by porting the scale math and replaying a captured `/api/candles`
payload at four viewport sizes — volume bars inside the bottom 20%, histogram bars inside the right
30%, candles inside the plot box, adjacent bars separated, and sparse time gaps proportional.

**Not verified:** the JavaScript has never been executed. No browser or JS engine was available in the
build environment, so the front end is covered only by served-asset assertions, the geometry replay
and a structural scan. Someone should open both consoles and look at them.

## Out of scope / later

Authentication and a bind address for the control endpoints (root DESIGN §7.8). Book-depth
visualisation, an order blotter, and per-candle volume-by-price (the histogram is window-aggregate).
