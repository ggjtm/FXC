# FXC investor load harness (Locust)

Generates continuous OFX order flow against **FxcBroker**, with a browser UI to steer it live — both
*how much* load and *what kind of investor* produces it. See
`../FxcInvestor/docs/stories/006-locust-multi-agent-runner.md` (the harness) and
`../FxcInvestor/docs/stories/007-investor-mix-control.md` (the mix).

**Why Locust and not Gatling.** Gatling OSS reads every `sim.*` knob in a static initializer, so a run
is frozen the moment its JVM starts, and its HTML report is generated only *after* the run ends. There
is no way to start, stop, or re-rate a run in progress; that capability lives only in the commercial
Gatling Enterprise. Locust ships a web UI that does exactly that.

## Run it

**Container (the demo path).** Brought up automatically by `scripts/demo.sh`, which now runs
continuously by default; or on its own:

```sh
docker compose up -d locust        # builds on first use
open http://localhost:8089
```

`scripts/demo.sh --no-load` starts the stack without it; `scripts/demo.sh --batch` runs the old bounded
20-tick walkthrough and exits.

It comes up **idle** (`LOCUST_AUTOSTART=false`): users, spawn rate and the mix shares are prefilled, and
nothing runs until you press **Start**. That matches the exchange, which now boots with the market
halted — adding load is a deliberate act you can show rather than something already happening when you
open the page. The prefilled 8 users / spawn rate 2 is roughly 5 orders/sec, significant for a laptop
and far short of a load test; change users and spawn rate in the UI *while it runs*, which is the point
of Locust. Set `FXC_LOADGEN_AUTOSTART=true` for an unattended run.

**Host virtualenv (for iterating on the harness).** `scripts/loadtest.sh` does the venv setup, checks
the broker is actually up, and passes anything it does not recognise straight through to `locust`:

```sh
scripts/demo.sh --no-load &          # a stack without the containerized harness
scripts/loadtest.sh                  # web UI on :8089, from loadgen/ — no image rebuild
scripts/loadtest.sh --users 20 --spawn-rate 5
scripts/loadtest.sh --mix-rando 1 --mix-booker 4 --mix-bookfish 2
scripts/loadtest.sh --strategy bookfish
```

Reports land in `build/locust-reports/`.

## Steering the investor mix

All three investor types run at once — **one of each by default**. *Number of users* stays the total
and *Spawn rate* stays live; the three `mix-*` fields beside them are **shares** of that total:

| Number of users | mix-rando | mix-booker | mix-bookfish | you get |
|---|---|---|---|---|
| 8 | 0 | 0 | 0 | 3 / 3 / 2 — all-zero means one of each |
| 16 | 4 | 10 | 2 | 4 / 10 / 2 — shares that sum to the total *are* the counts |
| 16 | 1 | 4 | 2 | 2 / 9 / 5 |
| 12 | 0 | 1 | 0 | 0 / 12 / 0 — a zero share means none of that type |
| 50 | 1 | 999 | 0 | 1 / 49 / 0 — a requested type is never dropped silently |

Change any of them mid-run (the **Edit** button, or `POST /swarm`) and the population re-balances within
about a second without restarting the run:

```sh
curl -X POST localhost:8089/swarm \
  -d 'user_count=16&spawn_rate=2&mix_rando=1&mix_booker=4&mix_bookfish=2'
```

`--strategy bookfish` is still honoured as shorthand for "all investors of one type"; any explicit
`--mix-*` value overrides it.

**How it works, and why it matters when reading the UI.** The strategy is an *attribute* of an investor,
not a user class, so a re-mix moves live investors between strategies rather than spawning or killing
any — the oldest keep theirs, and everyone keeps their account, RNG and connection. Locust therefore
shows **one** user class and no per-class breakdown; the mix is reported in the log
(`[fxc-loadgen] mix: rando=2 booker=9 bookfish=5 of 16 investors`) and visible in the per-strategy stats
rows below. Per-class user counts were the obvious alternative and cannot be changed mid-run — see
`../docs/PROBLEMS.md` P16 for the autopsy.

## One account per investor

Each investor opens its own broker account at startup (`FxcInvestor/docs/stories/004`), so the broker
console at :8083 shows one P&L curve per investor rather than a blend of everything sharing a dev
account. Identity is a **slot**, not a spawn counter: an investor claims the lowest free slot on start
and releases it on stop, so `locust-0…locust-15` — and their accounts — are reused across ramps and
re-mixes instead of opening a fresh funded account per spawn.

If the console is unreachable or the broker has `account.open.enabled = false`, the harness logs it
once and falls back to sharing `--accounts`. It never fails a run over it.

## Reading the UI

**Every OFX failure comes back as HTTP 200.** A wrong password returns a signon-only envelope; a
misspelled tag makes the broker silently skip the entire message set. A harness that trusted HTTP
status would show unbroken green while placing no orders at all.

The stats table is therefore split, and the business rows carry the strategy that produced them in the
**Type** column:

| Row | Meaning |
|---|---|
| `POST ofx-order` | the HTTP call. Fails only on real faults: transport error, non-200, unparseable body, rejected signon, or a missing order response. |
| `RANDO/BOOKER/BOOKFISH accepted` | orders the broker accepted, per investor type. **These are the rows that tell you the harness is working.** |
| `… rejected:<reason>` | business rejections, broken out by the broker's own reason text. Not failures — the system saying no is the system working. Expect these from `rando`, which is naive by design. |
| `… skipped` | the strategy had no opinion, or the liquidity policy declined (e.g. before the first statement read succeeds). |
| `BOOKFISH skipped:no-edge` | patience: it had a view but the drawn price offered no advantage. Normal, and roughly half of its ticks. |
| `BOOKFISH skipped:not-ready` | patience: not enough traded volume to form a view yet. Persistent means the market-wide feed is not reaching it — check `--exchange-url`. |

If no `accepted` row is climbing, nothing is reaching the exchange no matter how green the HTTP row is.

## Configuration

Every option takes an env var (`FXC_*` below, or any `LOCUST_*` Locust option) or a CLI flag; run
`locust -f locustfile.py --help` for the full list. Options also appear in the web UI's start/edit form;
the mix shares and the patience thresholds are re-read on a timer, so a change lands within about a
second, and the rest take effect on the next swarm.

| Env | CLI | Default | |
|---|---|---|---|
| `FXC_OFX_USER` | `--ofx-user` | `investor` | must match FxcBroker's `ofx.user` |
| `FXC_OFX_PASSWORD` | `--ofx-password` | `secret` | must match `ofx.password` — a mismatch is invisible in HTTP status |
| `FXC_BROKER_CONSOLE_URL` | `--broker-console-url` | *(empty)* | FxcBroker console; each investor opens **its own account** there, so the console's per-account P&L is per investor. Empty falls back to `--accounts` |
| `FXC_CLIENT_PREFIX` | `--client-prefix` | `locust` | client-id prefix for opened accounts (`locust-0`, `locust-1`, …) |
| `FXC_ACCOUNTS` | `--accounts` | `000000001,000000002` | shared fallback (the market-maker accounts), used only when opening is unavailable; investors spread round-robin |
| `FXC_SYMBOLS` | `--symbols` | `ARVX` | |
| `FXC_MIX_RANDO` | `--mix-rando` | `0` | share of users running `rando`; all three at 0 means **one of each** |
| `FXC_MIX_BOOKER` | `--mix-booker` | `0` | |
| `FXC_MIX_BOOKFISH` | `--mix-bookfish` | `0` | |
| `FXC_STRATEGY` | `--strategy` | *(empty)* | shorthand for a single-type run; any `--mix-*` value wins over it |
| `FXC_MIX_REFRESH_MS` | `--mix-refresh-ms` | `1000` | how often the live population is reconciled with the shares |
| `FXC_SEED` | `--seed` | `1` | each user derives `seed + index`, so a run is reproducible |
| `FXC_SEED_PRICE` | `--seed-price` | `42.10` | fallback last sale on a cold market |
| `FXC_PORTFOLIO_REFRESH_MS` | `--portfolio-refresh-ms` | `5000` | how often cash/positions are re-read over OFX |
| `FXC_EXCHANGE_URL` | `--exchange-url` | *(empty)* | FxcExchange base URL for market-wide traded volume; empty means `bookfish` sees only this process's fills |
| `FXC_MARKET_FEED_REFRESH_MS` | `--market-feed-refresh-ms` | `10000` | how often that feed is re-read |
| `FXC_MARKET_FEED_WINDOW_MS` | `--market-feed-window-ms` | `900000` | how far back it aggregates; shorter tracks the market faster |
| `FXC_BOOKFISH_MIN_VOLUME` | `--bookfish-min-volume` | `50` | traded volume `bookfish` wants before trading at all |
| `FXC_BOOKFISH_MIN_EDGE_TICKS` | `--bookfish-min-edge-ticks` | `1` | ticks of advantage it requires; `0` makes it trade whenever it has data |
| `FXC_MAX_P95_MS` | `--max-p95-ms` | `0` (off) | fail the run if the `ofx-order` p95 exceeds this |
| `FXC_MAX_ERROR_PCT` | `--max-error-pct` | `0` (off) | fail the run if the `ofx-order` failure ratio exceeds this percentage |
| `FXC_MIN_ACCEPTED` | `--min-accepted` | `0` (off) | fail the run if fewer orders than this were accepted |

Compose reads `FXC_LOADGEN_*` for the container (`FXC_LOADGEN_USERS`, `FXC_LOADGEN_MIX_BOOKER`,
`FXC_LOADGEN_EXCHANGE_URL`, `FXC_LOADGEN_AUTOSTART`, …).

### Gated runs

The three threshold options restore what Gatling's `sim.maxP95Ms`/`maxErrorPct` assertions did: they are
evaluated when the run ends and set the process exit code, so a headless run can gate a pipeline.

```sh
scripts/loadtest.sh --headless --run-time 1m --users 8 \
  --max-p95-ms 1000 --max-error-pct 1 --min-accepted 100; echo $?
```

Latency is measured on the `ofx-order` HTTP row, not the aggregate — the synthetic business counters are
zero-latency by construction and would flatter any percentile. Note that locust's own
`--exit-code-on-error` already fails a run with transport errors; these add the business criteria.

## Tests

The codec and strategy logic are tested with the **standard library only** — no pip install needed:

```sh
cd loadgen && python3 -m unittest discover -s tests -t .
```

`tests/test_population.py` needs locust and skips itself when it is absent, so the command above stays
pip-free; it runs in the virtualenv and in the image:

```sh
docker run --rm -v "$PWD/loadgen:/w" -v "$PWD/FxcInvestor/sample_data:/FxcInvestor/sample_data" \
  -w /w --entrypoint python fxc/locust:2.32.5 -m unittest discover -s tests -t .
```

The important ones are in `tests/test_ofx.py`: they assert this harness builds **byte-identical** OFX
to the Java client, against fixtures in `../FxcInvestor/sample_data/` that are generated and guarded by
`OfxGoldenEnvelopeTest`. That shared fixture is what stops the two implementations drifting — and given
that drift would be silent, it is the highest-value test here.

To regenerate the fixtures after an intentional format change:

```sh
FXC_WRITE_GOLDEN=1 ./gradlew :FxcInvestor:test --tests '*OfxGoldenEnvelopeTest'
```

Review that diff carefully: it is a change to this harness's contract too.

## Layout

```
locustfile.py              InvestorUser, the population registry + mix reconciler, stats + gates
fxc_loadgen/ofx.py         OFX 2.x builder + response parser (no OFX4J in Python)
fxc_loadgen/strategies.py  rando / booker / bookfish ports + the shared histogram sampler
fxc_loadgen/liquidity.py   cash-scaled sizing + liquidity floor (twin of LiquidityAwareStrategy)
fxc_loadgen/patience.py    bookfish's wait-for-an-advantage gate (twin of PatientStrategy)
fxc_loadgen/mix.py         shares -> counts -> minimal-churn strategy reassignment
fxc_loadgen/marketfeed.py  market-wide traded volume from the exchange's chart feed
fxc_loadgen/accounts.py    slot pool + per-investor broker accounts (stories/004)
fxc_loadgen/instruments.py tick + lot table, mirroring fxc-common's InstrumentCatalog
tests/                     stdlib unittest (tests/test_population.py skips itself without locust)
```

Deliberately **not** a Gradle module: `./gradlew build` is byte-for-byte unaffected by this directory,
which is how the repo's framework-free convention survives adding a second language.
