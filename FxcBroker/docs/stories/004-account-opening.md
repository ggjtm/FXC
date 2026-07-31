# An account per trading agent
Status: done
Relates to: root DESIGN §2/§4.2/§4.4/§7.8 / FxcInvestor/docs/stories/007 / [003](003-rolling-pnl-window.md)

## Summary

Every trading agent — the two Java investors and each Locust virtual investor — now opens **its own
broker account** and trades only that. `POST /api/accounts?clientId=…` returns the account for a client
id, opening one the first time it is asked, so the broker console's per-account P&L is one agent's
performance rather than a blend of everything sharing a dev account.

## Motivation

Accounts existed only because `FxcBroker.Main` seeded `account.dev` and `account.dev2` at startup;
there was no way to open one. Two Java agents and 8–16 Locust investors shared those two, which made
the console's headline chart misleading in a specific way: a curve labelled `000123456` was the
combined P&L of a `booker`, a `bookfish` and a naive `rando`, and no amount of staring at it separated
them. It also meant `rando` drew down the same balances the liquidity policy was defending for the
others (recorded as a risk in FxcInvestor/docs/stories/007).

## What it does

**`POST /api/accounts?clientId=locust-3&ownerName=…`** → `{"account":"000100042","opened":true}`
(`201` when it created one, `200` when it found an existing one).

- **Query parameters in, JSON out** — the same shape the exchange's control POSTs take, and for the
  same reason: this repo has a hand-rolled JSON *writer* and deliberately no parser, so no request
  carries a JSON body.
- **Idempotent per client id.** `ACCOUNT` gained a `client_id` column and an index;
  `AccountService.openAccount` returns the existing account for a client id it has seen. Agents
  restart and Locust investors respawn — a second account for the same trader would fragment its P&L
  and strand a funded balance nobody trades.
- **Funded with cash only** (`account.open.seedShares = 0`), $100,000 apiece (`account.open.seedCash`)
  — a tenth of a market maker's working capital, because an investor is retail-sized next to a desk
  holding the float. Every investor gets the *same* figure, so their curves stay comparable; different
  starting equity would make two agents' curves incomparable for a reason invisible on the chart. Shares are
  deliberately *not* seeded: an opened account carrying stock mints it, so adding investors to a
  running demo inflated the float instead of bringing capital to it and drove the price down 23%
  (root PROBLEMS.md P19). The float belongs to the seeded dev accounts.
- **Numbered from `account.open.first` (100000)**, zero-padded and allocated as max+1 under the service
  lock, so an account number says where it came from. **Below 100 is the broker's own** — the issuer at
  0 and the market makers at 1 and 2 — and the console filters those out (`pnl.internalAccountsBelow`):
  they hold the whole float, and mark-to-market on it dwarfs every customer's P&L. The number is the
  classification, so there is no flag to set at seeding time and no way for an account to be internal in
  one place and a customer in another.
- **Gated by its own `account.open.enabled`**, not by `web.controls.enabled`. Making the console
  read-only for an operator must not stop agents from opening accounts, and a broker that must not
  mint accounts should not have to give up the stop-trading control to say so.
- **`PnlService` baselines an account at the moment it opens** (`AccountOpenedListener`), the same way
  `captureBaselines()` does at startup. Without it a mid-session account's curve would start at its
  first fill, showing that one trade as the entire session's move.

**Client identity, and why it is not a spawn counter.** The Java agents pass `agent.clientId`
(`investor-a` / `investor-b` from `scripts/demo.sh`). Locust investors derive theirs from a **slot**:
each claims the lowest free slot on start and releases it on stop, so `locust-0…locust-15` are reused
across ramps and re-mixes. An ever-increasing index would have opened a funded account per spawn —
hundreds per demo, nearly all idle within a minute.

**Never fatal.** A broker with opening disabled, an unreachable console, an unparseable reply: the
agent falls back to its configured/shared account and says so once. Losing a *private* account should
not stop an agent trading.

## The trade this makes

An investor now speaks **two protocols** to the broker: OFX for everything it does repeatedly, and one
REST call at startup. OFX has no account-opening message set, and inventing a third custom one
(`FXCACCTMSGSRQV1`, alongside the order and book sets) for a call each agent makes exactly once bought
nothing over the broker's existing HTTP surface. DESIGN §4.4 states the seam rather than hiding it.

The endpoint is **unauthenticated and binds `0.0.0.0`**, like the rest of the console — but it *creates
funded state* rather than mutating existing state, which is a wider hole than the controls beside it.
Recorded in DESIGN §7.8; `account.open.enabled = false` closes it.

GridGain is in-memory, so a broker restart forgets accounts and the same client id gets a fresh number.
That is the same "a restart starts a new session" rule the P&L already follows.

## Acceptance criteria

- [x] Each agent trades an account only it uses, and the console shows one curve per agent.
- [x] The same client id always gets the same account, across restarts and respawns.
- [x] Account count settles at the high-water mark of concurrent agents, not the number of spawns.
- [x] An opened account is funded with cash and can trade immediately.
- [x] Opening accounts never changes the tradable float.
- [x] Its P&L curve is anchored at zero when it opens, not at its first fill.
- [x] Opening can be disabled without disabling the console, and vice versa.
- [x] An agent that cannot open one still trades.

## Verification

`AccountOpeningTest` (11): opens with cash and no shares, **opening 20 accounts leaves the float
untouched**, share seeding still works when asked for, idempotent per client id, distinct numbers,
numbering above the seeded accounts, tradable immediately, refused when disabled, blank client id
refused, listeners fire once per account, and the P&L baseline captured at open. `BrokerWebApiTest` (+2): the endpoint's
201/200/400 behaviour and the separate disable switch. Python `tests/test_accounts.py` (16): slot reuse
across a ramp, caching, failure paths; `tests/test_population.py` (+3) for the locust wiring.

Live: see root DESIGN §6.5.

## Out of scope / later

Authentication (root DESIGN §7.8 covers the whole console). Closing an account. Persisting accounts
across a broker restart — they live in GridGain hot state with everything else. Per-agent funding
policies: every agent starts with the same balance on purpose.
