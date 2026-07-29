# FXC investor load harness (Locust)

Generates continuous OFX order flow against **FxcBroker**, with a browser UI to steer it live.
Replaces the retired Gatling harness — see `../FxcInvestor/docs/stories/006-locust-multi-agent-runner.md`.

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

It begins swarming immediately (`LOCUST_AUTOSTART=true`) at 8 users / spawn rate 2 — roughly 5
orders/sec, significant for a laptop and far short of a load test. Change users and spawn rate in the
UI while it runs; that is the point.

**Host virtualenv (for iterating on the harness).** `scripts/loadtest.sh` does the venv setup, checks
the broker is actually up, and passes anything it does not recognise straight through to `locust`:

```sh
scripts/demo.sh --no-load &          # a stack without the containerized harness
scripts/loadtest.sh                  # web UI on :8089, from loadgen/ — no image rebuild
scripts/loadtest.sh --users 20 --spawn-rate 5
scripts/loadtest.sh --strategy bookfish
```

Reports land in `build/locust-reports/`.

## Reading the UI

**Every OFX failure comes back as HTTP 200.** A wrong password returns a signon-only envelope; a
misspelled tag makes the broker silently skip the entire message set. A harness that trusted HTTP
status would show unbroken green while placing no orders at all.

The stats table is therefore split:

| Row | Meaning |
|---|---|
| `POST ofx-order` | the HTTP call. Fails only on real faults: transport error, non-200, unparseable body, rejected signon, or a missing order response. |
| `ORDER accepted` | orders the broker accepted. **This is the row that tells you the harness is working.** |
| `ORDER rejected:<reason>` | business rejections, broken out by the broker's own reason text. Not failures — the system saying no is the system working. |
| `ORDER skipped` | the strategy had no opinion (e.g. `rando` with no last sale yet). |

If `ORDER accepted` is not climbing, nothing is reaching the exchange no matter how green the HTTP row is.

## Configuration

Every option takes an env var (`FXC_*` below, or any `LOCUST_*` Locust option) or a CLI flag; run
`locust -f locustfile.py --help` for the full list.

| Env | CLI | Default | |
|---|---|---|---|
| `FXC_OFX_USER` | `--ofx-user` | `investor` | must match FxcBroker's `ofx.user` |
| `FXC_OFX_PASSWORD` | `--ofx-password` | `secret` | must match `ofx.password` — a mismatch is invisible in HTTP status |
| `FXC_ACCOUNTS` | `--accounts` | `000123456,000654321` | users spread round-robin, so both P&L lines move |
| `FXC_SYMBOLS` | `--symbols` | `ACME` | |
| `FXC_STRATEGY` | `--strategy` | `rando` | |
| `FXC_SEED` | `--seed` | `1` | each user derives `seed + index`, so a run is reproducible |
| `FXC_SEED_PRICE` | `--seed-price` | `42.10` | fallback last sale on a cold market |

Compose reads `FXC_LOADGEN_*` for the container (`FXC_LOADGEN_USERS`, `FXC_LOADGEN_AUTOSTART`, …).

## Tests

The codec and strategy logic are tested with the **standard library only** — no pip install needed:

```sh
cd loadgen && python3 -m unittest discover -s tests -t .
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
locustfile.py            Locust User classes — the entry point
fxc_loadgen/ofx.py       OFX 2.x builder + response parser (no OFX4J in Python)
fxc_loadgen/strategies.py rando / booker / bookfish ports
fxc_loadgen/instruments.py tick + lot table, mirroring fxc-common's InstrumentCatalog
tests/                   stdlib unittest
```

Deliberately **not** a Gradle module: `./gradlew build` is byte-for-byte unaffected by this directory,
which is how the repo's framework-free convention survives adding a second language.
