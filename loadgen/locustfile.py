"""FXC investor load harness (FxcInvestor/docs/stories/006).

Replaces the retired Gatling harness. The reason is capability, not taste: Locust's web UI can
**start, stop, and re-rate a run while it is running**, whereas every Gatling ``sim.*`` knob was read
in a static initializer and frozen at JVM start.

Run it::

    locust -f loadgen/locustfile.py --host http://localhost:8082

then open **http://localhost:8089** to swarm. Or headless::

    locust -f loadgen/locustfile.py --host http://localhost:8082 \\
           --headless --users 8 --spawn-rate 2

Reading the UI — the one thing to understand
--------------------------------------------
**Every OFX failure comes back as HTTP 200.** A wrong password yields a signon-only envelope; a
misspelled tag makes the broker silently skip the whole message set. A harness that trusted HTTP
status would show a flat green wall of success while placing no orders whatsoever.

So the stats table is deliberately split:

``POST ofx-order``
    the HTTP call. Marked **failed** only for genuine faults — transport errors, a non-200, an
    unparseable body, a rejected signon, or a missing order response.
``ORDER accepted`` / ``ORDER rejected:<reason>``
    synthetic counters for the *business* outcome. A broker rejection ("insufficient shares for
    equity sell") is **not** a load-test failure — it is the system working — so it never colours the
    failure ratio, but it is always visible, broken out by reason.

If ``ORDER accepted`` is not climbing, no orders are reaching the exchange, whatever the HTTP row says.
"""

from __future__ import annotations

import itertools
import os
import random
import time
from decimal import Decimal

from locust import HttpUser, between, events, task

from fxc_loadgen import instruments, liquidity, ofx, strategies

# --------------------------------------------------------------------------- configuration


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


@events.init_command_line_parser.add_listener
def _add_arguments(parser):
    """Harness knobs, exposed as real CLI options so ``locust --help`` documents them.

    Environment variables supply the defaults so the container can be configured without rewriting
    the command line.
    """
    parser.add_argument("--ofx-user", default=_env("FXC_OFX_USER", "investor"),
                        help="OFX signon user (must match FxcBroker's ofx.user)")
    parser.add_argument("--ofx-password", default=_env("FXC_OFX_PASSWORD", "secret"),
                        help="OFX signon password (must match FxcBroker's ofx.password)")
    parser.add_argument("--accounts", default=_env("FXC_ACCOUNTS", "000123456,000654321"),
                        help="comma-separated accounts; users are spread across them round-robin")
    parser.add_argument("--symbols", default=_env("FXC_SYMBOLS", "ACME"),
                        help="comma-separated symbols to trade")
    parser.add_argument("--strategy", default=_env("FXC_STRATEGY", "rando"),
                        help="strategy name: rando (naive) | booker | bookfish (liquidity-managed)")
    parser.add_argument("--seed", type=int, default=int(_env("FXC_SEED", "1")),
                        help="base RNG seed; each user derives seed+index for reproducibility")
    parser.add_argument("--seed-price", default=_env("FXC_SEED_PRICE", "42.10"),
                        help="fallback last-sale used until the book reports one")
    parser.add_argument("--portfolio-refresh-ms", type=int,
                        default=int(_env("FXC_PORTFOLIO_REFRESH_MS", "5000")),
                        help="how often to re-read cash/positions over OFX; the liquidity-managed "
                             "strategies size orders from this. An interval, not per order: the "
                             "broker's OFX server is a fixed 4-thread pool.")


#: Assigns each spawning user a stable index, for account spread and seed derivation.
_user_index = itertools.count()


def _fire(name: str, request_type: str = "ORDER", exception=None) -> None:
    """Emit a synthetic stats row so business outcomes show up in the UI.

    ``response_time=0`` because these are counters, not timings — the real latency lives on the
    ``POST ofx-order`` row.
    """
    events.request.fire(
        request_type=request_type,
        name=name,
        response_time=0,
        response_length=0,
        exception=exception,
        context={},
    )


class InvestorUser(HttpUser):
    """One autonomous investor placing OFX orders against FxcBroker.

    ``wait_time`` paces roughly one order every 1–2 s per user, so the default 8 users produce ~5
    orders/sec aggregate — significant for a laptop, well short of a load test. Turning it up is an
    explicit action in the UI, which is the point of using Locust.
    """

    wait_time = between(1.0, 2.0)

    def on_start(self) -> None:
        options = self.environment.parsed_options
        index = next(_user_index)

        accounts = [a.strip() for a in options.accounts.split(",") if a.strip()]
        symbols = [s.strip() for s in options.symbols.split(",") if s.strip()]
        if not accounts or not symbols:
            raise ValueError("--accounts and --symbols must each name at least one value")

        self.account = accounts[index % len(accounts)]
        self.symbols = symbols
        self.ofx_user = options.ofx_user
        self.ofx_password = options.ofx_password
        # Per-user derived seed, mirroring how the retired Gatling sim did `SEED + userId`.
        self.rng = random.Random(options.seed + index)
        self.strategy = strategies.by_name(options.strategy)
        self.market = strategies.MarketView()
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

        # Seed a fallback last sale so the first ticks have something to price against; the strategy
        # returns None without one, and rando would otherwise never place an order on a cold market.
        seed_price = Decimal(options.seed_price)
        for symbol in self.symbols:
            self.market.set_last_sale(symbol, seed_price)

    def _client_order_id(self) -> str:
        # Must be unique: the broker keys its order map on TRNUID, so a duplicate silently
        # overwrites prior order state. Index + run tag + counter keeps it unique across restarts.
        return f"LOC-{self.index}-{self.run_tag}-{next(self.orders)}"

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
        symbol = self.rng.choice(self.symbols)
        self._refresh_portfolio_if_stale()
        intent = self.strategy.decide(symbol, self.market, self.portfolio, self.rng)
        if intent is None:
            _fire("skipped")
            return

        quantity = instruments.snap_to_lot(symbol, intent.quantity)
        if quantity <= 0:
            _fire("skipped")
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
            _fire("accepted")
        else:
            reason = (outcome.message or outcome.order_status or "unknown").strip()
            _fire(f"rejected:{reason[:60]}")

        # Feed the book back into the market view so book-driven strategies have depth to sample
        # (step 3). The order and book rode one envelope, so this cost no extra round trip.
        if parsed.book:
            self.market.set_book(
                symbol, [(lvl.side, Decimal(str(lvl.price)), Decimal(str(lvl.size))) for lvl in parsed.book]
            )
        if parsed.last_price is not None:
            self.market.record_trade(symbol, Decimal(str(parsed.last_price)), quantity)


@events.test_start.add_listener
def _announce(environment, **_kwargs):
    options = environment.parsed_options
    print(
        f"[fxc-loadgen] target={environment.host} strategy={options.strategy} "
        f"accounts={options.accounts} symbols={options.symbols} seed={options.seed}"
    )
    print(
        "[fxc-loadgen] watch the 'ORDER accepted' row — a green 'POST ofx-order' row alone does "
        "NOT mean orders are reaching the exchange"
    )


@events.quitting.add_listener
def _summary(environment, **_kwargs):
    stats = environment.stats
    accepted = stats.get("accepted", "ORDER")
    print(f"[fxc-loadgen] orders accepted: {accepted.num_requests}")
    for name, entry in sorted(stats.entries.items()):
        if name[0].startswith("rejected:"):
            print(f"[fxc-loadgen] {name[0]}: {entry.num_requests}")
