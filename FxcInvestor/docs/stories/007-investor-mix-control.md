# Investor mix control, and a patient bookfish
Status: done
Relates to: root DESIGN §2 / §6.5 / follows story [006](006-locust-multi-agent-runner.md) / changes
[003](003-bookfish-agent.md)

## Summary

The load harness ran **one strategy per process**. It now runs all three at once — **one of each by
default** — with the number of each type steerable from the Locust UI *while the run is in progress*,
alongside the user count and spawn rate that story 006 delivered. `bookfish` gained the market-wide
signal it never had in Python, and with it a reason to wait: it declines to trade until it can name an
advantage.

## Motivation

Three gaps, all inherited from the Gatling migration:

1. **No population mix.** The retired simulation bucketed users by `Math.floorMod(uid, 100)` against
   `sim.mix.rando` / `sim.mix.booker`; the Locust harness took a single `--strategy`. A demo that shows
   one kind of investor is not showing a market.
2. **`bookfish` was `rando` in disguise.** Its histogram came only from this process's own OFX
   responses, and a thin histogram silently takes `histogram_sample`'s uniform ±1% fallback. Nothing
   in the stats table distinguished that from the real thing.
3. **No pass/fail.** Gatling's `sim.maxP95Ms` / `maxErrorPct` assertions went with it, so a headless
   run always exited 0 and could not gate anything.

## Flow

1. `scripts/demo.sh` brings up the stack and the harness, which autostarts at 8 users — now **3 rando,
   3 booker, 2 bookfish** rather than 8 booker.
2. Each virtual investor trades as before (holdings over OFX on an interval, order + book snapshot in
   one envelope), but all three types share **one process-wide `MarketView`**, so their observations
   pool.
3. A greenlet polls the exchange's public `GET /api/candles` and installs its `volumeByPrice` array as
   the market-wide traded-volume histogram — the signal a Java agent gets over XMPP.
4. `bookfish` consults that histogram: too little volume, or a draw with no edge against fair value, and
   it abstains with a reason instead of guessing.
5. The operator opens `http://localhost:8089`, edits *Number of users*, *Spawn rate* and the three
   `mix-*` shares, and the population re-balances within about a second — no restart.

## As built

```
loadgen/
  locustfile.py              InvestorUser (one class, reassignable strategy), the POPULATION registry,
                             _reconcile_mix + its timer, the feed poller, per-strategy stats rows,
                             threshold gates
  fxc_loadgen/mix.py         shares -> counts (proportional, zero repaired) -> minimal-churn reassignment
  fxc_loadgen/patience.py    bookfish's readiness + edge gates (twin of PatientStrategy)
  fxc_loadgen/marketfeed.py  volumeByPrice from the exchange chart feed, tolerant parse
  fxc_loadgen/strategies.py  histogram_stats extracted; MarketView.load_traded_volume added
  tests/                     +81 tests (mix, patience, marketfeed, population)
docker-compose.yml           FXC_MIX_*, FXC_EXCHANGE_URL; FXC_STRATEGY no longer pinned to booker
scripts/loadtest.sh          defaults FXC_EXCHANGE_URL to the local exchange
```

Java-side changes, so the strategy means one thing in both languages:

- `strategy/PatientStrategy` — the twin of `patience.py`, wired by `Strategies.byName` for `bookfish`
  only, inside `LiquidityAwareStrategy`.
- `strategy/HistogramSampling.stats()` — extracted from `sample()` so the gate and the sampler read the
  same weighted centre. `sample()`'s behaviour is unchanged, which `HistogramSamplerTest` still proves.
- `LiquidityAwareStrategy.delegate()` — package-private accessor, so the wrapping order can be asserted
  rather than assumed.

## Approach notes

**Shares of the population, not absolute counts.** The UI's own *Number of users* field is the
capability story 006 exists for; the mix scales it rather than replacing it. Shares that happen to sum
to the population *are* the counts (`4/10/2` of 16 investors is 4/10/2), which makes the two readings
agree whenever it matters. Apportionment is proportional (Hare quota, largest remainder, integer
arithmetic) and then **repairs any zero** by borrowing from the largest holder — so a lopsided mix like
`1/999` still runs one `rando`, and a requested type is never silently absent. When the population cannot
cover every requested type, the ones that do not fit are *logged* (once the ramp has settled).

**The strategy is an attribute, not a user class.** The obvious design — one `User` subclass per strategy
and `fixed_count` from the apportionment — starts correctly and **cannot be changed while a run is in
progress**; the full autopsy is docs/PROBLEMS.md P16. So the ownership is inverted: locust owns the
population *size* (and the ramping, and the UI), and this file owns what each member of it *does*. A
one-second timer applies `mix.reassign`, which moves the fewest investors it can and leaves the oldest
alone. Reassignment is free — the investor keeps its account, RNG, portfolio view and connection; only
its decision function changes — which is why a re-mix shows up in the stats within a second and costs
nothing.

Consequences worth having: `--run-time`, *Number of users* and *Spawn rate* all keep locust's native
behaviour (a `LoadTestShape` would have taken over stopping the run and had to re-implement the time
limit), a ramp-down that kills the wrong strategies is self-healing on the next tick, and **no locust
internals are touched**.

**Three locust behaviours this does depend on, verified in the 2.32.5 source rather than assumed:**

| Behaviour | Consequence here |
|---|---|
| `POST /swarm` writes every custom arg back into `parsed_options`, type-preserving | this is the live-steering channel; no custom UI needed |
| A custom arg whose default is `None` can never be changed from the UI (the handler writes the old `None` back) | every new option has a real `int`/`str` default; `--strategy` defaults to `""`, not `None` (PROBLEMS P14) |
| `parsed_options` gains a `users` key from the UI while argparse owns `num_users` | avoided entirely: the reconciler apportions across the live registry, not across an option (PROBLEMS P15) |

**Patience is a decorator, not a change to the sampler** — the same shape as `LiquidityAwareStrategy`,
and for the same reason: `bookfish`'s draw stays directly comparable to the Java original, and the
existing sampler tests still pass untouched. Order is `LiquidityPolicy(PatienceGate(bookfish))`; see
[003](003-bookfish-agent.md#patience-waiting-for-an-advantage) for why the gate must be innermost and
why being stateless is what stops it starving the liquidity floor.

**The candle feed is a read of public market data, not a new order path.** Orders still go only to
FxcBroker over OFX. It is the same JSON the exchange's own browser console reads, on an interval, with a
tolerant parse: a transport failure is logged once (never silently swallowed — a dead feed would look
exactly like a market with no volume, the one thing patience must not be wrong about), and the harness
falls back to pooled local observations.

**`rando` is now in the demo by default**, sharing the two seeded accounts with the managed strategies.
Its drift is a near-symmetric random walk, so the effect is occasional visible rejections rather than a
drain, and the liquidity policy on the other two defends the floor. Isolating it would need a third
seeded account (`FxcBroker.Main` seeds exactly two) — deliberately not done here.

## Acceptance criteria

- [x] The number of each investor type is settable from the Locust UI, live, without restarting the run.
- [x] One of each type runs by default, in the demo as well as the harness.
- [x] A requested type is never silently dropped; what does not fit is logged.
- [x] `--strategy` still means a single-type run (backwards compatible).
- [x] Business outcomes are readable per strategy in the stats table.
- [x] `bookfish` declines rather than falling back to a uniform draw, with a distinguishable reason.
- [x] `bookfish` still trades regularly once it has market-wide volume.
- [x] An abstention consumes the same RNG draws as an order (a seeded run is unchanged by patience).
- [x] Patience cannot prevent a forced liquidity sell, only delay it by a tick.
- [x] Patience is implemented in Java too, with matching tests.
- [x] A headless run can fail on p95, error ratio, or orders accepted.
- [x] `./gradlew build` is still unaffected by `loadgen/`.

## Verification

- **Python**: 202 stdlib `unittest` tests (105 before, +35 mix, +26 patience, +18 market feed, +18
  population), 0 failures. The 18 population tests skip on a bare host and run in the image, so
  `python3 -m unittest discover` stays pip-free.
- **Java**: 180 tests, 0 failures — `PatientStrategyTest` (24) added;
  `RandoStrategyTest`/`HistogramSamplerTest`/`LiquidityAwareStrategyTest` pass **unchanged**, which is
  the proof that `rando`, `booker` and the liquidity policy were not touched.
- **Live mix steering**: a running demo at 8 investors (3/3/2) was re-mixed with
  `curl -X POST :8089/swarm -d 'user_count=16&spawn_rate=4&mix_rando=1&mix_booker=4&mix_bookfish=2'`
  and reached 2/9/5 of 16 within seconds — no restart, no stats reset, every row continuing.
- **Live patience, A/B against the feed** (30 s, 4 bookfish investors, same live stack):

  | `--exchange-url` | accepted | `skipped:no-edge` | `skipped:not-ready` |
  |---|---|---|---|
  | *(empty)* | **0** | 0 | 81 |
  | `http://localhost:8090` | **38** | 41 | 4 (warm-up only) |

  Without market-wide volume it places no orders at all rather than falling back to a uniform draw;
  with it, it trades steadily and abstains about half the time. That contrast is the story.
- **Gates**: `--max-p95-ms 1000 --min-accepted 20` → two `PASS` lines, exit 0;
  `--max-p95-ms 1 --min-accepted 100000` → `FAIL ofx-order p95 11 ms (limit 1 ms)`,
  `FAIL orders accepted 45 (minimum 100000)`, `thresholds breached: p95, accepted`, exit 1. Both runs
  stopped themselves on `--run-time`, which is locust's own behaviour and stays that way because there
  is no shape class.
- **Live continuous run** (2026-07-29): 6 minutes at 8 investors — 1,457 orders posted, **1,457
  accepted, 0 rejected, 0 transport failures**, `ofx-order` p95 12 ms; `RANDO` 631 / `BOOKER` 628 /
  `BOOKFISH` 198 accepted with 225 `skipped:no-edge`; exchange at ~5.2 trades/sec; both accounts
  mean-reverting on the seeded 1,042,000 baseline (+509, +270). Full table in root DESIGN §6.5.

## Out of scope / later

Pacing knobs (Gatling's `sim.pauseMs`): `wait_time` stays `between(1.0, 2.0)`. Load-profile shapes
(`ramp`/`steady`/`spike`): live UI re-rating covers the demo need, and only one shape class can exist per
locustfile. A third seeded account to isolate `rando`. An XMPP client in Python (the candle feed stands
in). Distributed master/worker. Java↔Python seed parity, which remains impossible (48-bit LCG vs
Mersenne Twister).
