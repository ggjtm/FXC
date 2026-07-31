"""FXC investor load harness (FxcInvestor/docs/stories/006, 007).

Replaces the retired Gatling harness. The reason is capability, not taste: Locust's web UI can
**start, stop, and re-rate a run while it is running**, whereas every Gatling ``sim.*`` knob was read
in a static initializer and frozen at JVM start.

Run it::

    locust -f loadgen/locustfile.py --host http://localhost:8082

then open **http://localhost:8089** to swarm. Or headless::

    locust -f loadgen/locustfile.py --host http://localhost:8082 \\
           --headless --users 8 --spawn-rate 2

Steering the investor mix
-------------------------
Three kinds of investor run at once — ``rando``, ``booker`` and ``bookfish``, **one of each by
default**. *Number of users* and *Spawn rate* stay exactly as locust ships them; the three ``mix-*``
fields beside them are **shares** of the population.

The strategy is an *attribute* of an investor, not a user class, so changing a share moves live
investors between strategies instead of spawning or killing anything: `_reconcile_mix` re-reads the
options once a second (the same channel the UI writes them on) and applies
``fxc_loadgen.mix.reassign``, which moves the minimum number of investors and leaves the oldest alone.
Locust keeps owning the population size; this file owns what each member of it does. Per-class user
counts were the obvious alternative and cannot express a live mix — see docs/PROBLEMS.md P16.

``--strategy bookfish`` is still honoured as shorthand for "all investors of one type"; an explicit
``--mix-*`` value overrides it.

Reading the UI — the one thing to understand
--------------------------------------------
**Every OFX failure comes back as HTTP 200.** A wrong password yields a signon-only envelope; a
misspelled tag makes the broker silently skip the whole message set. A harness that trusted HTTP
status would show a flat green wall of success while placing no orders whatsoever.

So the stats table is deliberately split, and the business rows are tagged with the strategy that
produced them:

``POST ofx-order``
    the HTTP call. Marked **failed** only for genuine faults — transport errors, a non-200, an
    unparseable body, a rejected signon, or a missing order response.
``RANDO accepted`` / ``BOOKER rejected:<reason>`` / ``BOOKFISH skipped:no-edge``
    synthetic counters for the *business* outcome, per strategy. A broker rejection ("insufficient
    shares for equity sell") is **not** a load-test failure — it is the system working — so it never
    colours the failure ratio, but it is always visible, broken out by reason.

If no ``accepted`` row is climbing, no orders are reaching the exchange, whatever the HTTP row says.
``BOOKFISH skipped:*`` climbing on its own is not a fault either: ``bookfish`` waits for an advantage
(``fxc_loadgen.patience``), and ``skipped:not-ready`` specifically means it has not seen enough traded
volume yet — point ``--exchange-url`` at FxcExchange to give it the whole market's.
"""

from __future__ import annotations

import itertools
import os
import random
import time
from decimal import Decimal

import gevent
from locust import HttpUser, between, events, task
from locust.runners import STATE_RUNNING

from fxc_loadgen import accounts, instruments, liquidity, marketfeed, mix, ofx, patience, strategies

# --------------------------------------------------------------------------- configuration


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


def _log(message: str) -> None:
    print(f"[fxc-loadgen] {message}", flush=True)


@events.init_command_line_parser.add_listener
def _add_arguments(parser):
    """Harness knobs, exposed as real CLI options so ``locust --help`` documents them.

    Environment variables supply the defaults so the container can be configured without rewriting
    the command line. Every default is a real value rather than ``None`` on purpose: locust's
    ``/swarm`` handler writes a ``None`` default straight back (docs/PROBLEMS.md P14), so an option
    defaulting to ``None`` can never be changed from the web UI.
    """
    parser.add_argument("--ofx-user", default=_env("FXC_OFX_USER", "investor"),
                        help="OFX signon user (must match FxcBroker's ofx.user)")
    parser.add_argument("--ofx-password", default=_env("FXC_OFX_PASSWORD", "secret"),
                        help="OFX signon password (must match FxcBroker's ofx.password)")
    parser.add_argument("--accounts", default=_env("FXC_ACCOUNTS", "000000001,000000002"),
                        help="comma-separated accounts, used only when account opening is off or "
                             "unavailable; investors are spread across them round-robin")
    parser.add_argument("--broker-console-url", default=_env("FXC_BROKER_CONSOLE_URL", ""),
                        help="FxcBroker console base URL (e.g. http://localhost:8083). Each investor "
                             "opens its own account there, so its P&L on the console is its own. "
                             "Empty falls back to --accounts.")
    parser.add_argument("--client-prefix", default=_env("FXC_CLIENT_PREFIX", accounts.DEFAULT_PREFIX),
                        help="client-id prefix for opened accounts; keeps two harness processes from "
                             "claiming each other's accounts")
    parser.add_argument("--symbols", default=_env("FXC_SYMBOLS", "ACME"),
                        help="comma-separated symbols to trade")
    parser.add_argument("--strategy", default=_env("FXC_STRATEGY", ""),
                        help="shorthand for a single-type run: rando | booker | bookfish. Empty "
                             "means use the mix below (one of each). Any --mix-* value overrides it.")
    parser.add_argument("--mix-rando", type=int, default=int(_env("FXC_MIX_RANDO", "0")),
                        help="share of investors running rando; 0 with the others also 0 means "
                             "'one of each'")
    parser.add_argument("--mix-booker", type=int, default=int(_env("FXC_MIX_BOOKER", "0")),
                        help="share of investors running booker")
    parser.add_argument("--mix-bookfish", type=int, default=int(_env("FXC_MIX_BOOKFISH", "0")),
                        help="share of investors running bookfish")
    parser.add_argument("--seed", type=int, default=int(_env("FXC_SEED", "1")),
                        help="base RNG seed; each user derives seed+index for reproducibility")
    parser.add_argument("--seed-price", default=_env("FXC_SEED_PRICE", "42.10"),
                        help="fallback last-sale used until the book reports one")
    parser.add_argument("--portfolio-refresh-ms", type=int,
                        default=int(_env("FXC_PORTFOLIO_REFRESH_MS", "5000")),
                        help="how often to re-read cash/positions over OFX; the liquidity-managed "
                             "strategies size orders from this. An interval, not per order: the "
                             "broker's OFX server is a fixed 4-thread pool.")
    parser.add_argument("--exchange-url", default=_env("FXC_EXCHANGE_URL", ""),
                        help="FxcExchange base URL (e.g. http://localhost:8090) for market-wide "
                             "traded volume; empty disables it and bookfish sees only this "
                             "process's own fills")
    parser.add_argument("--market-feed-refresh-ms", type=int,
                        default=int(_env("FXC_MARKET_FEED_REFRESH_MS", "10000")),
                        help="how often to re-read the exchange's volume-by-price feed")
    parser.add_argument("--market-feed-window-ms", type=int,
                        default=int(_env("FXC_MARKET_FEED_WINDOW_MS",
                                         str(marketfeed.DEFAULT_WINDOW_MS))),
                        help="how far back that feed aggregates; shorter tracks the market faster")
    parser.add_argument("--bookfish-min-volume", type=int,
                        default=int(_env("FXC_BOOKFISH_MIN_VOLUME",
                                         str(int(patience.DEFAULT_MIN_VOLUME)))),
                        help="traded volume bookfish wants to see before it will trade at all")
    parser.add_argument("--bookfish-min-edge-ticks", type=int,
                        default=int(_env("FXC_BOOKFISH_MIN_EDGE_TICKS",
                                         str(patience.DEFAULT_MIN_EDGE_TICKS))),
                        help="ticks of advantage against fair value bookfish requires; 0 makes it "
                             "trade on every tick it has data for")
    parser.add_argument("--mix-refresh-ms", type=int, default=int(_env("FXC_MIX_REFRESH_MS", "1000")),
                        help="how often the live population is reconciled with the mix shares")
    parser.add_argument("--max-p95-ms", type=int, default=int(_env("FXC_MAX_P95_MS", "0")),
                        help="fail the run (exit 1) if the ofx-order p95 exceeds this; 0 = off")
    parser.add_argument("--max-error-pct", type=float, default=float(_env("FXC_MAX_ERROR_PCT", "0")),
                        help="fail the run if the ofx-order failure ratio exceeds this percentage; "
                             "0 = off")
    parser.add_argument("--min-accepted", type=int, default=int(_env("FXC_MIN_ACCEPTED", "0")),
                        help="fail the run if fewer orders than this were accepted; 0 = off")


#: Assigns each spawning user a stable index, for account spread and seed derivation.
_user_index = itertools.count()

#: One market view for the whole process, shared by every virtual investor.
#:
#: Two reasons. It is what makes ``bookfish``'s traded-volume histogram worth sampling — sixteen
#: investors pooling observations see sixteen times the market one does — and it is where the exchange
#: feed (:func:`_poll_market_feed`) lands. Locust runs on gevent, so this is cooperative
#: single-threaded access: no locking, and no torn reads to reason about. Per-investor state that must
#: *not* be shared (the RNG, the portfolio, the strategy instance and its patience gate) stays on the
#: user.
MARKET = strategies.MarketView()

#: Live investors, oldest first — the population :func:`_reconcile_mix` reassigns across strategies.
#:
#: Maintained by ``on_start``/``on_stop`` rather than read out of locust, because locust's own registry
#: is greenlets, not users. A plain list is right: it preserves spawn order (so the oldest investors
#: keep their strategy through a re-mix) and stays cheap at the scale a laptop can drive.
POPULATION: list[InvestorUser] = []

#: Set once the feed poller and the mix reconciler are running, so a second test start does not start
#: a second one of either.
_feed_greenlet = None
_mix_greenlet = None

#: Slots, client ids and the account each holds — built at test start when a console URL is configured
#: (``fxc_loadgen.accounts``). One per process, so a re-mix or a ramp reuses identities instead of
#: opening an account per spawn.
_registry = None

#: Logged once rather than per investor: a broker that will not open accounts says so at the same
#: volume whether it is refusing one investor or sixteen.
_open_failure_reported = False


def _split(value: str) -> list[str]:
    return [item.strip() for item in value.split(",") if item.strip()]


def _fire(name: str, request_type: str = "ORDER", exception=None) -> None:
    """Emit a synthetic stats row so business outcomes show up in the UI.

    ``request_type`` carries the strategy, which is what splits the rows per investor type in a mixed
    run. ``response_time=0`` because these are counters, not timings — the real latency lives on the
    ``POST ofx-order`` row, which is also the row the ``--max-p95-ms`` gate reads for exactly that
    reason.
    """
    events.request.fire(
        request_type=request_type,
        name=name,
        response_time=0,
        response_length=0,
        exception=exception,
        context={},
    )


# --------------------------------------------------------------------------- the investor


class InvestorUser(HttpUser):
    """One autonomous investor placing OFX orders against FxcBroker.

    Its **strategy is an attribute**, reassignable while it runs (:func:`_reconcile_mix`). Everything
    else about it — account, RNG, portfolio view, client-order-id sequence — survives a strategy change,
    so re-mixing costs no connection churn and no loss of history.

    ``wait_time`` paces roughly one order every 1–2 s per investor, so the default 8 produce ~5
    orders/sec aggregate — significant for a laptop, well short of a load test. Turning it up is an
    explicit action in the UI, which is the point of using Locust.
    """

    wait_time = between(1.0, 2.0)

    def on_start(self) -> None:
        options = self.environment.parsed_options
        index = next(_user_index)

        fallback_accounts = _split(options.accounts)
        symbols = _split(options.symbols)
        if not fallback_accounts or not symbols:
            raise ValueError("--accounts and --symbols must each name at least one value")

        self.slot = None
        self.account = self._claim_account(fallback_accounts, index)
        self.symbols = symbols
        self.ofx_user = options.ofx_user
        self.ofx_password = options.ofx_password
        # Per-user derived seed, mirroring how the retired Gatling sim did `SEED + userId`.
        self.rng = random.Random(options.seed + index)
        self.market = MARKET
        # booker/bookfish come back wrapped in the liquidity policy, which declines to trade without
        # holdings data — so the portfolio has to be real, refreshed on an interval like the Java
        # agent's PortfolioCache.
        self.portfolio = liquidity.Portfolio()
        self.portfolio_refresh_ms = options.portfolio_refresh_ms
        self.portfolio_checked_at = 0.0
        self.orders = itertools.count(1)
        self.index = index
        # Locust's User has no start_time; take our own so client order ids stay unique across
        # restarts of the harness (the broker keys its order map on TRNUID).
        self.run_tag = int(time.time())

        self.strategy_name: str | None = None
        self.strategy = None
        self.patience = None
        self.stats_type = "ORDER"

        # Seed a fallback last sale so the first ticks have something to price against; the strategy
        # returns None without one, and rando would otherwise never place an order on a cold market.
        seed_price = Decimal(options.seed_price)
        for symbol in self.symbols:
            self.market.last_sale.setdefault(symbol, seed_price)

        POPULATION.append(self)
        # Claim a strategy immediately rather than waiting up to a second for the reconciler: a user
        # with no strategy cannot trade, and a ramp-up would otherwise idle. Quietly, because a ramp of
        # N users would otherwise log N intermediate mixes — including "did not fit" for the first two
        # investors of a three-strategy mix, which is true for a moment and misleading in a log.
        _reconcile_mix(self.environment, announce=False)

    def on_stop(self) -> None:
        try:
            POPULATION.remove(self)
        except ValueError:
            pass
        # Give the slot back so the next investor is this same client rather than a new one — that is
        # what stops a ramp cycle opening a fresh account every time (stories/004).
        if self.slot is not None and _registry is not None:
            _registry.release(self.slot)
            self.slot = None

    def _claim_account(self, fallback_accounts: list[str], index: int) -> str:
        """This investor's own account, or a share of the seeded ones if opening is unavailable."""
        global _open_failure_reported
        if _registry is not None:
            try:
                self.slot, account = _registry.claim()
                return account
            except accounts.AccountError as error:
                if not _open_failure_reported:
                    _open_failure_reported = True
                    _log(f"could not open an account, sharing {fallback_accounts} instead: {error}")
        return fallback_accounts[index % len(fallback_accounts)]

    def adopt(self, name: str, options) -> None:
        """Switch to a strategy, and take the current patience thresholds.

        Called on spawn and on every reconciliation. Adopting the strategy it already runs must be a
        no-op on the *instance*: rebuilding it would reset the liquidity policy's cash baseline once a
        second, quietly disabling the floor it exists to defend. The thresholds are re-read either way,
        so editing them in the UI takes effect on the next pass.
        """
        if name != self.strategy_name:
            self.strategy_name = name
            self.strategy = strategies.by_name(name)
            # bookfish comes back wrapped in the patience gate; hold on to it so an abstention can be
            # reported with its reason rather than as an anonymous skip.
            self.patience = patience.gate_of(self.strategy)
            self.stats_type = name.upper()
        if self.patience is not None:
            self.patience.min_volume = Decimal(options.bookfish_min_volume)
            self.patience.min_edge_ticks = options.bookfish_min_edge_ticks

    def _fire(self, name: str) -> None:
        _fire(name, request_type=self.stats_type)

    def _client_order_id(self) -> str:
        # Must be unique: the broker keys its order map on TRNUID, so a duplicate silently
        # overwrites prior order state. Index + run tag + counter keeps it unique across restarts.
        return f"LOC-{self.index}-{self.run_tag}-{next(self.orders)}"

    def _skip_reason(self) -> str:
        """``skipped`` or ``skipped:<why>`` — the difference between "quiet" and "broken"."""
        reason = self.patience.last_reason if self.patience is not None else None
        return f"skipped:{reason}" if reason else "skipped"

    def _refresh_portfolio_if_stale(self) -> None:
        """Re-read cash and positions if the cached view has aged out.

        A statement request cannot ride the order's envelope — both are the `investment` message-set
        type, and the broker's type-ordered set would silently drop one — so this is its own call,
        reported separately in the stats table.
        """
        now = time.monotonic() * 1000.0
        if self.portfolio_checked_at and now - self.portfolio_checked_at < self.portfolio_refresh_ms:
            return
        # Paced on the attempt, not the success, so an unreachable broker is not retried every order.
        self.portfolio_checked_at = now

        body = ofx.build_statement_request(
            self.account, user=self.ofx_user, password=self.ofx_password
        )
        with self.client.post(
            "/ofx",
            data=body,
            headers={"Content-Type": ofx.CONTENT_TYPE},
            name="ofx-statement",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"HTTP {response.status_code}")
                return
            try:
                parsed = ofx.parse_response(response.content)
            except Exception as error:  # noqa: BLE001
                response.failure(f"could not parse statement: {error}")
                return
            if not parsed.signon_ok:
                response.failure(f"signon rejected (CODE={parsed.signon_code})")
                return
            response.success()

        if parsed.cash or parsed.shares:
            self.portfolio = liquidity.Portfolio(
                cash_by_currency={k: Decimal(str(v)) for k, v in parsed.cash.items()},
                shares={k: Decimal(str(v)) for k, v in parsed.shares.items()},
            )

    @task
    def place_order(self) -> None:
        if self.strategy is None:  # not yet assigned a strategy; the reconciler is about to
            return
        symbol = self.rng.choice(self.symbols)
        self._refresh_portfolio_if_stale()
        intent = self.strategy.decide(symbol, self.market, self.portfolio, self.rng)
        if intent is None:
            self._fire(self._skip_reason())
            return

        quantity = instruments.snap_to_lot(symbol, intent.quantity)
        if quantity <= 0:
            self._fire("skipped")
            return

        cl_ord_id = self._client_order_id()
        body = ofx.build_order_with_book_request(
            self.account,
            cl_ord_id,
            symbol,
            intent.side,
            intent.price,
            quantity,
            depth=5,
            user=self.ofx_user,
            password=self.ofx_password,
        )

        with self.client.post(
            "/ofx",
            data=body,
            headers={"Content-Type": ofx.CONTENT_TYPE},
            name="ofx-order",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                # 405 = wrong method; 400 = the broker could not parse the envelope (an empty
                # element is the classic cause).
                response.failure(f"HTTP {response.status_code}: {response.text[:160]}")
                return
            try:
                parsed = ofx.parse_response(response.content)
                outcome = ofx.require_order(parsed)
            except ofx.OfxProtocolError as error:
                # A rejected signon or a missing order message set: HTTP said 200 but nothing traded.
                # This is the failure mode the harness exists to make loud.
                response.failure(str(error))
                return
            except Exception as error:  # noqa: BLE001 - unparseable body is still a real failure
                response.failure(f"could not parse OFX reply: {error}")
                return

            response.success()

        # Business outcome, off the failure ratio but always visible.
        if outcome.accepted:
            self._fire("accepted")
        else:
            reason = (outcome.message or outcome.order_status or "unknown").strip()
            self._fire(f"rejected:{reason[:60]}")

        # Feed the book back into the market view so book-driven strategies have depth to sample
        # (step 3). The order and book rode one envelope, so this cost no extra round trip.
        if parsed.book:
            self.market.set_book(
                symbol, [(lvl.side, Decimal(str(lvl.price)), Decimal(str(lvl.size))) for lvl in parsed.book]
            )
        if parsed.last_price is not None:
            self.market.record_trade(symbol, Decimal(str(parsed.last_price)), quantity)


# --------------------------------------------------------------------------- the mix


#: The last mix logged, so a reconciliation that changes nothing stays quiet.
_reported_mix: dict[str, int] | None = None
#: The last mix complaint logged, so an unusable share is reported once rather than every second.
_reported_complaint: str | None = None


def _reconcile_mix(environment, announce: bool = True) -> None:
    """Move live investors between strategies until the population matches the mix shares.

    Idempotent and cheap, so it can run on a timer and on every spawn. Nothing here spawns or kills a
    user: the population size belongs to locust (and therefore to the UI's own *Number of users*), and
    what each member of it *does* belongs here.

    ``announce=False`` suppresses the log line, for the mid-ramp calls where the population is still
    growing and any mix it reports is already out of date.
    """
    global _reported_mix, _reported_complaint
    options = environment.parsed_options
    if not POPULATION:
        return

    try:
        shares = mix.resolve_shares(
            options.mix_rando, options.mix_booker, options.mix_bookfish, options.strategy
        )
    except ValueError as error:
        # An operator typo in the UI must not take the run down, or stop later edits from working.
        if _reported_complaint != str(error):
            _reported_complaint = str(error)
            _log(f"ignoring unusable mix: {error}")
        return
    _reported_complaint = None

    counts, dropped = mix.apportion(len(POPULATION), shares)
    assignment = mix.reassign([user.strategy_name for user in POPULATION], counts)
    for user, name in zip(POPULATION, assignment):
        if name is not None:
            user.adopt(name, options)

    if announce and counts != _reported_mix:
        _reported_mix = counts
        _log(f"mix: {mix.format_mix(counts) or '(none)'} of {len(POPULATION)} investors")
        # Never silently truncate: the operator asked for these and is not getting them. Only once the
        # ramp has settled, though — mid-spawn the population is smaller than it is about to be, and a
        # warning about that is true for a second and wrong afterwards.
        if dropped and getattr(environment.runner, "state", None) == STATE_RUNNING:
            _log(f"mix: {', '.join(dropped)} did not fit in {len(POPULATION)} investors — "
                 "raise the user count")


def _watch_mix(environment) -> None:
    """Re-read the mix options on an interval; the UI writes them straight into ``parsed_options``."""
    interval = max(0.2, environment.parsed_options.mix_refresh_ms / 1000.0)
    while True:
        gevent.sleep(interval)
        try:
            _reconcile_mix(environment)
        except Exception as error:  # noqa: BLE001 - this greenlet must outlive any single failure
            _log(f"mix reconciliation failed: {error}")


# --------------------------------------------------------------------------- market feed


def _poll_market_feed(environment) -> None:
    """Keep :data:`MARKET`'s traded-volume histograms fed from the exchange's public chart feed.

    Best effort by design: the first failure is logged, repeats are not (a down exchange would
    otherwise drown the console), and recovery is logged once. ``bookfish`` degrades to this process's
    own observations, which is what it did before the feed existed — and its patience gate reports
    ``skipped:not-ready`` while that lasts, so the degradation is visible rather than silent.
    """
    options = environment.parsed_options
    base_url = options.exchange_url.strip()
    interval = max(1.0, options.market_feed_refresh_ms / 1000.0)
    failing = False
    while True:
        for symbol in _split(options.symbols):
            try:
                histogram = marketfeed.fetch_volume_by_price(
                    base_url, symbol, window_ms=options.market_feed_window_ms
                )
            except marketfeed.MarketFeedError as error:
                if not failing:
                    failing = True
                    _log(f"market feed unavailable, using local observations only: {error}")
                continue
            if failing:
                failing = False
                _log("market feed recovered")
            MARKET.load_traded_volume(symbol, histogram)
        gevent.sleep(interval)


# --------------------------------------------------------------------------- lifecycle


@events.test_start.add_listener
def _announce(environment, **_kwargs):
    global _feed_greenlet, _mix_greenlet, _registry, _open_failure_reported
    options = environment.parsed_options

    console_url = options.broker_console_url.strip()
    if console_url and _registry is None:
        _registry = accounts.AccountRegistry(console_url, prefix=options.client_prefix)
        _open_failure_reported = False
        _log(f"each investor opens its own account at {console_url} "
             f"(client ids {options.client_prefix}-0, -1, …)")
    elif not console_url:
        _log(f"no --broker-console-url: investors share {options.accounts}, so the broker console's "
             "per-account P&L blends them")
    _log(
        f"target={environment.host} accounts={options.accounts} symbols={options.symbols} "
        f"seed={options.seed}"
    )
    try:
        shares = mix.resolve_shares(
            options.mix_rando, options.mix_booker, options.mix_bookfish, options.strategy
        )
        _log(f"mix shares: {mix.format_mix(shares)} (change them in the UI while it runs)")
    except ValueError as error:
        _log(f"unusable mix: {error}")
    _log(
        f"bookfish patience: min volume {options.bookfish_min_volume}, "
        f"min edge {options.bookfish_min_edge_ticks} tick(s)"
    )

    if _mix_greenlet is None:
        _mix_greenlet = gevent.spawn(_watch_mix, environment)

    if options.exchange_url.strip() and _feed_greenlet is None:
        _log(
            f"market-wide traded volume from {options.exchange_url.strip()} every "
            f"{options.market_feed_refresh_ms} ms"
        )
        _feed_greenlet = gevent.spawn(_poll_market_feed, environment)
    elif not options.exchange_url.strip():
        _log(
            "no --exchange-url: bookfish sees only this process's fills and will report "
            "'skipped:not-ready' until enough volume accumulates"
        )

    _log(
        "watch the '<STRATEGY> accepted' rows — a green 'POST ofx-order' row alone does NOT mean "
        "orders are reaching the exchange"
    )


def _accepted_by_strategy(stats) -> dict[str, int]:
    return {
        request_type: entry.num_requests
        for (name, request_type), entry in stats.entries.items()
        if name == "accepted"
    }


@events.quitting.add_listener
def _summary(environment, **_kwargs):
    stats = environment.stats
    accepted = _accepted_by_strategy(stats)
    for request_type in sorted(accepted):
        _log(f"orders accepted [{request_type}]: {accepted[request_type]}")
    _log(f"orders accepted [total]: {sum(accepted.values())}")
    for name, entry in sorted(stats.entries.items()):
        if name[0].startswith("rejected:") or name[0].startswith("skipped:"):
            _log(f"{name[1]} {name[0]}: {entry.num_requests}")

    _check_thresholds(environment, sum(accepted.values()))


def _check_thresholds(environment, accepted_total: int) -> None:
    """Turn the run into a pass/fail, restoring what Gatling's ``sim.max*`` assertions used to do.

    Measured on the ``ofx-order`` HTTP row rather than the aggregate, because the aggregate includes
    the synthetic zero-latency business counters and would flatter any latency threshold.
    """
    options = environment.parsed_options
    orders = environment.stats.get("ofx-order", "POST")
    failures: list[str] = []

    if options.max_p95_ms > 0:
        p95 = orders.get_response_time_percentile(0.95) or 0
        verdict = "FAIL" if p95 > options.max_p95_ms else "PASS"
        _log(f"{verdict} ofx-order p95 {p95} ms (limit {options.max_p95_ms} ms)")
        if verdict == "FAIL":
            failures.append("p95")

    if options.max_error_pct > 0:
        error_pct = orders.fail_ratio * 100.0
        verdict = "FAIL" if error_pct > options.max_error_pct else "PASS"
        _log(f"{verdict} ofx-order failures {error_pct:.2f}% (limit {options.max_error_pct:.2f}%)")
        if verdict == "FAIL":
            failures.append("errors")

    if options.min_accepted > 0:
        verdict = "FAIL" if accepted_total < options.min_accepted else "PASS"
        _log(f"{verdict} orders accepted {accepted_total} (minimum {options.min_accepted})")
        if verdict == "FAIL":
            failures.append("accepted")

    if failures:
        # Locust honours this as the process exit code, so a headless run can gate a pipeline.
        environment.process_exit_code = 1
        _log(f"thresholds breached: {', '.join(failures)}")
