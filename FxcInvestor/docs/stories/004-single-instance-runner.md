# Single-instance runner

Status: in-progress
Relates to: PLAN items 2–5; root DESIGN §4.4

## Summary

Run **one** FxcInvestor agent instance: connect to a broker over OFX (and, optionally, to FxcPub
over XMPP for the feed), then drive the decision loop with a selected `Strategy`
(`rando`/`booker`/`bookfish`) until stopped. This is the developer/demo entry point and the shell
the autonomous agents plug into.

## Behavior

- **Config** (`conf/fxcinvestor.conf` + `-D` overrides): OFX broker URL + credentials, account
  number, XMPP coordinates, `agent.strategy` (`rando|booker|bookfish`), tick interval, RNG seed,
  instrument universe to trade.
- **OFX client**: signon → statement sync (positions/balances) → submit orders via the custom
  order-entry message set.
- **Feed ingestion** (optional): subscribe to the broker's FxcPub feed; update `MarketView`
  (last-sale, traded-volume histogram) from fill statuses.
- **Decision loop**: each tick builds `MarketView` + `PortfolioView`, calls
  `Strategy.decide(...)`, and submits the resulting order (if any). `agent on|off` gates the loop;
  a thin CLI REPL (`buy sell positions orders feed post agent on|off quit`) is the interactive face.
- **Persistence**: append each decision to the MariaDB decision log (best-effort; no-op if DB
  absent).

## Acceptance criteria

- `agent on` with `strategy=rando` trades autonomously end-to-end against a live broker/exchange and
  its fills appear on the FxcPub feed (root Phase 3 exit path).
- Strategy is selectable by config without code changes.
- Runs headless (for demos/CI) and interactively (REPL).

## Notes

- The runner owns the RNG (seedable) and the tick clock, so a single agent is fully reproducible.
- This runner drives ONE agent in-process; bulk/perf simulation is the separate Gatling runner
  ([005](005-gatling-multi-agent-runner.md)).
