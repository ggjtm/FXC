# Exchange feeds

## Status
implemented

Implemented in `com.fxc.exchange.feed` and the FIX layer:

- **FIX raw data (exchange → broker), three depth tiers** — `MarketDataService` honors FIX
  `MarketDepth(264)`: `1` = top of book, `5` = market depth (five levels/side), `0` = full depth of
  book. Every snapshot also carries the **last sale** as an `MDEntryType=TRADE` entry
  (`MarketDataDepthTest`).
- **REST feed service (aggregated candles)** — `FeedHttpServer` (`GET /api/candles`) returns OHLCV
  candles + a volume-by-price histogram for `symbol` over `[start,end)` at a requested granularity.
  Age-based minimum-granularity floors are enforced (`Granularities`): a window touching data older
  than one week is floored to one-day candles, older than six months to one-week candles, with a
  one-minute overall floor. Trades are read from the GridGain `TRADE` hot table unioned with the
  `TRADE_ARCHIVE` cold table (a `ts` epoch-millis column was added to both). Candle math is pure and
  unit-tested (`CandleAggregatorTest`); the REST path is integration-tested (`FeedHttpServerIntegrationTest`).
- **Live ticker WebSocket** — a hand-rolled RFC 6455 server (`WebSocketFeedServer`) plus `LiveFeed`
  push one-second aggregated tick windows (last sale + volume grouped and summed by price) to
  subscribers; a connection with no `symbol` filter receives the full feed of all securities
  (`WebSocketFeedServerTest`, driven by the JDK `WebSocket` client).
- **Web UI (exchange → public)** — a self-contained canvas charting page (`web/index.html`, served
  at `/`): security / interval (default current day) / style (lines or candles) / granularity
  controls, a live WebSocket update when the interval end is unset, a bottom red/green volume strip,
  and an underlaid right-side 30%-transparent volume-by-price histogram.

Enable via `feed.enabled` / `feed.http.port` / `feed.ws.port` in `conf/fxcexchange.conf`; open
`http://localhost:8090/` once the exchange is running.

## Original story

## Summary

Lets a broker request 3 levels of price quotations
1. Top of Book: price and volume pair for each of the top bid, ask, and last sale for each traded security.
2. Market Depth: Top of Book data, plus four more top bid and ask price/volume pairs to provide five bids and asks each.
3. Full Depth of Book: same data as the order book snapshot, plus last sale.

Lets a broker subscribe to ticker feeds (last sale price and volume for each transaction, grouped by price and summed by volume at a minimum interval of one second windows). 

Lets the exchange report a full ticker feed of all transactions on all securities.

## Motivation

Everyone involved plus external interests wants market surveillance that illustrates price action over time.

## Flow

** exchange -> broker (FIX):** Raw exchange data, standard functionality

** exchange -> investor/broker (REST feed service):** Aggregated data, time bucketed minimally to one minute, and provided as candle data (open, close, high, low) with summed volume for each minute. Provided both as a snapshot with historical time interval start and end parameters as well as candle granularity. To control feed workload, requests for histories spanning or touching data that is more than one week old have a minimum granularity of one day, and histories spanning or touching data that is more than six months have a minimum granularity of one week. If the time interval does not have an end, a live data websocket is provided.

** exchange -> public (web UI):** The exchange will provide a web UI that taps the REST feed service for data, and provides widgets for a chart display and selecting the parameters for the data display. The security, time interval (defaulting to current day), chart style (lines or candles) and granularity. If the time interval selection end is unspecified, the chart will continuously update from a live data websocket. The chart display widgets should include a bottom strip bar chart displaying red and green bars for volume, and an underlaid right side vertical histogram of volume summed at each price point with 30% transparent bars extending from zero at the right edge of the price chart to max volume.