# Broker monitor/controller console
Status: done
Relates to: root DESIGN §6.3 / PLAN "Additional stories" / fxc-common story 001

## Summary

A dark-themed static web console at `http://localhost:8083/` with a mouseover dropdown to stop and
start trading, a ticker of last sale prices from the exchange feed across the top, and a D3 line plot
of each managed account's cumulative trade count against its session profit and loss.

## Motivation

Root DESIGN §6 asks for a broker pane whose data did not exist. Before this story:

- there was **no P&L code anywhere in the repo**;
- there was no way to list accounts (`AccountService` had no `accounts()`, `BrokerRepository` never
  read the `ACCOUNT` table);
- `EXECUTION` had **neither a timestamp nor an account**, so a fill could only be attributed to an
  account by joining `CLIENT_ORDER` — which the archiver deletes — meaning a session's P&L history was
  not derivable from stored data at all;
- last-sale prices existed in `MarketDataCache` but were private and FIX-fed;
- FxcBroker served **only** `POST /ofx` — no GET, no JSON, no static files;
- `OmsService.submit` always routed, so "starting and stopping trading" had nothing to switch.

## Flow

1. The page reads `/api/config`, then polls `/api/status`, `/api/pnl` and `/api/lastsale` once a
   second.
2. `/api/lastsale` renders the ticker, with an arrow glyph and a colour per symbol's move since the
   previous poll.
3. `/api/pnl` draws one line per account (x = cumulative trades, y = relative P&L) plus the legend and
   the table view.
4. A control item POSTs `/api/trading/stop` or `/api/trading/start` and refreshes.

## As built

- `pnl/FxRates` — currency → USD from the exchange's last sale: `USD` is 1, else `CCY/USD`, else the
  inverse of `USD/CCY`. The seeded universe covers every currency the demo uses (EUR/GBP/AUD directly,
  JPY inverted).
- `pnl/PnlPoint`, `pnl/PnlService` — baselines captured after seeding, one point appended per fill.
- `oms/FillListener` + `OmsService.addFillListener`, plus `tradingEnabled` and routed/fill/reject
  counters.
- `AccountService.accounts()`, `BrokerRepository.accounts()`, `MarketDataCache.lastPrices()`,
  `BrokerFixClient.isLoggedOn()`.
- `EXECUTION` and `EXECUTION_ARCHIVE` gain `account_number` and `ts` (cold schema bumped to v2, with
  idempotent `ALTER TABLE … IF NOT EXISTS` so an existing dev database migrates).
- `web/BrokerConsoleService`, `web/BrokerWebServer` on a **separate** HTTP server (`web.http.port`,
  default 8083), plus `web/index.html` + `web/assets/broker.js`.

## Approach notes

**P&L definition.** Equity is valued in USD as cash plus share positions marked at the exchange's last
sale; `relative` is equity now minus equity at session start. `realized` is closed-trade P&L (an
equity sell against the VWAP cost basis `AccountService` already maintains) and `unrealized` is the
remainder, so the two always sum to `relative`. An FX spot fill contributes nothing to `realized` — it
swaps one balance for another — and shows up through revaluation of the resulting balances instead.

**Why points are sampled on fills.** The broker does not historise marks, so a mark-to-market curve
cannot be reconstructed after the fact. Each point is computed at the moment a fill is applied, which
also matches what §6 asks for (trade count on the x axis). A restart starts a new session — the right
meaning of "over a trading session" for a demo component. The new `EXECUTION` columns are what make
the history archivable and reloadable, but the live series is the chart's source.

**Reading the cost basis after the fill is safe** because `AccountService.addShares` only updates
`avg_price` on buys, so an equity sell leaves the pre-sale basis intact. The listener therefore fires
*after* the position update and still sees the right basis. This is pinned by a test, because it is a
non-obvious dependency on another class's behaviour.

**Approximations are stated, not hidden.** A share with no last sale yet is marked at cost basis —
valuing a seeded holding at zero would invent an enormous gain on its first trade. A holding whose
currency has no resolvable USD rate is excluded and *counted* (`unpricedHoldings`), which the console
displays as a warning line rather than drawing the figure as if it were complete. The baseline is taken
once and never revised: if a balance that was unvaluable at session start becomes priceable later it
joins equity without having been in the baseline, and the difference reads as profit. That is left as a
stated limitation rather than papered over with a silent mid-session re-baseline, which would reset the
curve for reasons invisible to whoever is reading it. It does not arise in the seeded demo.

**A separate HTTP server, not a second context on the OFX one.** The OFX endpoint is POST-only and has
nothing in common with the console but a transport; keeping them apart lets the console be disabled
without touching OFX, and keeps the OFX handler unchanged.

**Polling, not a WebSocket.** One second of REST is enough for a status pane and a fill-driven curve,
needs no second WebSocket server, keeps working when the exchange's feed service is off, and reads the
broker's *own* market data rather than reaching past the broker to the exchange's port (AGENTS.md:
components stay loosely coupled through their own protocols).

**Colour follows the account**, assigned from the fixed categorical order by sorted account number, so
adding or removing an account never repaints the others. One y axis in USD relative to each account's
own session start, so accounts of different sizes stay comparable without a second scale.

## Acceptance criteria

- [x] `/api/status` reports the trading switch, the exchange link, counters and uptime.
- [x] `/api/pnl` reports baseline, equity, realized, unrealized, relative, trade count, unpriced count
      and the curve; `realized + unrealized == relative` at every point.
- [x] `/api/lastsale` is empty before anything trades, then reflects exchange trades.
- [x] Stopping trading makes `OmsService.submit` reject with an operator reason while the exchange
      stays open; starting restores routing.
- [x] Controls can be switched off, leaving a working read-only console and 404 on the POST paths.
- [x] The console page and every asset it references are served.

## Verification

`FxRatesTest` (6), `PnlServiceTest` (8, exact arithmetic against scripted fills and fixed marks,
including the FX and unpriced paths), `BrokerWebApiTest` (3, live broker + live exchange: endpoint
shapes, revaluation, the trading gate, read-only mode, and the served console).

**Not verified:** the JavaScript has never been executed — no browser or JS engine was available in the
build environment. The front end is covered only by served-asset assertions and a structural scan.
Someone should open the console and look at it.

## Out of scope / later

Authentication for the control endpoints (root DESIGN §7.8). Reloading the P&L curve from
`EXECUTION_ARCHIVE` after a restart (the columns are there; the read path is not). Per-account order
blotter. The broker's own XMPP bot leg (see this component's PLAN.md) is unrelated and still open.
