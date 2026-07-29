# FxcExchange — Stories

One markdown file per user story / work item for the exchange. Suggested naming:
`NNN-short-slug.md` (e.g. `001-matching-engine.md`, `002-market-data.md`). Keep each story small and
testable; link back to the component [PLAN.md](../PLAN.md) and root design where relevant.

Suggested front-matter per story:

```
# <title>
Status: proposed | in-progress | done
Relates to: PLAN item / DESIGN §
```

Phase 1 work (matching, FIX acceptor, market data, clearing) shipped before this stories folder
existed; backfill stories here if useful.

## Filed

- [`001-exchange feeds.md`](001-exchange%20feeds.md) — **implemented.** FIX quotes in three depth
  tiers + last sale, a framework-free REST OHLCV candle service, a hand-rolled RFC 6455 live-ticker
  WebSocket, and the charting web UI at `http://localhost:8090/`.
- [`002-exchange-control-ui.md`](002-exchange-control-ui.md) — **done.** The controller/monitor console
  (root DESIGN §6.2): trading-session halt/resume, order-book mass cancel with FIX `CANCELED`
  reporting, status/control REST endpoints, and the D3+SVG candle chart.
