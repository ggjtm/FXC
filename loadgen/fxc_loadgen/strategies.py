"""Investor strategies, ported from ``com.fxc.investor.strategy``.

The Java side splits a strategy into a *price-target sampler* and a shared ``SamplingStrategy`` that
picks the side and quantity. Same split here, so the ports stay comparable to their originals:

* side is a fair coin, quantity uniform in ``[1, 10]`` (``SamplingStrategy.MIN_QTY``/``MAX_QTY``)
* the sampler decides *where* in the price distribution to aim
* a strategy returning ``None`` means "no opinion this tick" — the Java equivalent of
  ``Optional.empty()``, which the agent loop logs as ``SKIPPED``

``rando`` is the naive one and stays naive. ``booker`` and ``bookfish`` are the non-naive ones and get
wrapped by the liquidity policy (see ``liquidity.py``) — that wrapping happens outside this module so
the samplers here remain pure and directly comparable to the Java originals.

Determinism: every strategy draws from a caller-supplied ``random.Random``, exactly as the Java
versions take a ``java.util.Random``. Seeding it per user makes a run reproducible.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Protocol

from . import instruments

__all__ = [
    "MarketView",
    "OrderIntent",
    "Strategy",
    "RandoStrategy",
    "BookerStrategy",
    "BookfishStrategy",
    "by_name",
    "NAIVE",
]

#: Quantity bounds shared by all strategies (SamplingStrategy.MIN_QTY / MAX_QTY).
MIN_QTY = 1
MAX_QTY = 10

#: ``rando`` ignores the portfolio; the others are liquidity-managed. Named here so callers do not
#: hardcode the distinction.
NAIVE = frozenset({"rando"})


@dataclass(frozen=True)
class OrderIntent:
    """What a strategy wants to do. Mirrors ``strategy.OrderIntent``."""

    side: str  # BUY | SELL
    price: Decimal
    quantity: Decimal


@dataclass
class MarketView:
    """Market state a strategy reads. Mirrors ``strategy.MarketView``.

    Populated from what the harness can actually observe over OFX: last sale and book depth come from
    the broker's snapshot relay; ``traded_volume`` is accumulated locally from this user's own
    observations, because Python has no XMPP feed. That is a **documented divergence** from the Java
    ``bookfish``, which builds its histogram from the shared feed and therefore sees the whole market.
    """

    last_sale: dict[str, Decimal] = field(default_factory=dict)
    book: dict[str, list[tuple[str, Decimal, Decimal]]] = field(default_factory=dict)
    traded_volume: dict[str, dict[Decimal, Decimal]] = field(default_factory=dict)

    def set_last_sale(self, symbol: str, price: Decimal) -> None:
        self.last_sale[symbol] = price

    def set_book(self, symbol: str, levels: list[tuple[str, Decimal, Decimal]]) -> None:
        self.book[symbol] = levels

    def record_trade(self, symbol: str, price: Decimal, quantity: Decimal) -> None:
        self.last_sale[symbol] = price
        bucket = self.traded_volume.setdefault(symbol, {})
        bucket[price] = bucket.get(price, Decimal(0)) + quantity


class Strategy(Protocol):
    """Mirrors ``strategy.Strategy``: a single decide() returning an intent or None."""

    name: str

    def decide(
        self, symbol: str, market: MarketView, portfolio: object, rng: random.Random
    ) -> OrderIntent | None: ...


def _side_and_quantity(rng: random.Random) -> tuple[str, Decimal]:
    """Side and quantity, exactly as ``SamplingStrategy.decide`` draws them."""
    side = "BUY" if rng.random() < 0.5 else "SELL"
    quantity = Decimal(rng.randint(MIN_QTY, MAX_QTY))
    return side, quantity


@dataclass
class RandoStrategy:
    """``rando``: price uniform in ``[last*(1-band), last*(1+band)]``, band 1%.

    Faithful port of ``RandoSampler`` + ``SamplingStrategy``. Naive by design — it ignores the
    portfolio entirely, so it will eventually be rejected for insufficient cash or shares. That is the
    Java behaviour too, and it is why the demo's sustainable flow comes from the non-naive strategies.
    """

    band: float = 0.01
    name: str = "rando"

    def decide(
        self, symbol: str, market: MarketView, portfolio: object, rng: random.Random
    ) -> OrderIntent | None:
        last = market.last_sale.get(symbol)
        if last is None:
            return None  # no last sale yet — Optional.empty() in Java
        factor = Decimal(str(1.0 + (rng.random() * 2.0 - 1.0) * self.band))
        side, quantity = _side_and_quantity(rng)
        return OrderIntent(
            side=side,
            price=instruments.snap_to_tick(symbol, last * factor),
            quantity=quantity,
        )


#: The uniform ±band fallback both histogram strategies share with ``rando``.
FALLBACK_BAND = 0.01


def _fallback_price(last_sale: Decimal, band: float, rng: random.Random) -> Decimal:
    """``rando``'s draw, reused when a histogram is unusable. Mirrors ``HistogramSampling.fallback``.

    Consumes exactly one random draw — the same as the histogram path — so which branch is taken does
    not shift the rest of the sequence.
    """
    factor = Decimal(str(1.0 + (rng.random() * 2.0 - 1.0) * band))
    return last_sale * factor


def histogram_sample(
    histogram: dict[Decimal, Decimal],
    last_sale: Decimal,
    sigma_mult: float,
    fallback_band: float,
    rng: random.Random,
) -> Decimal:
    """Weight-weighted price draw, restricted to ``sigma_mult`` σ of the last sale.

    Faithful port of ``HistogramSampling.sample``. The statistics are computed in ``float`` because
    the Java original does (``doubleValue()`` throughout), but the returned price is the bin's own
    ``Decimal`` key — so a value drawn from a book or trade histogram is already on the tick grid.

    Falls back to a uniform ±band draw when the histogram cannot support a distribution: fewer than
    two positive-weight bins, non-positive total weight, zero variance, or nothing inside the band.
    """
    # Stable, sorted, positive-weight bins — sorting is what makes the walk below deterministic.
    bins: list[tuple[Decimal, Decimal]] = sorted(
        (price, weight)
        for price, weight in histogram.items()
        if price is not None and weight is not None and weight > 0
    )
    if len(bins) < 2:
        return _fallback_price(last_sale, fallback_band, rng)

    total_weight = math.fsum(float(weight) for _, weight in bins)
    if total_weight <= 0:
        return _fallback_price(last_sale, fallback_band, rng)

    mean = math.fsum(float(weight) * float(price) for price, weight in bins) / total_weight
    variance = (
        math.fsum(float(weight) * (float(price) - mean) ** 2 for price, weight in bins) / total_weight
    )
    std = math.sqrt(variance)
    if std <= 0:
        # Every bin at one price: no distribution to sample from.
        return _fallback_price(last_sale, fallback_band, rng)

    band = sigma_mult * std
    last = float(last_sale)
    in_band = [(price, weight) for price, weight in bins if abs(float(price) - last) <= band]
    in_band_total = math.fsum(float(weight) for _, weight in in_band)
    if not in_band or in_band_total <= 0:
        return _fallback_price(last_sale, fallback_band, rng)

    target = rng.random() * in_band_total
    accumulated = 0.0
    for price, weight in in_band:
        accumulated += float(weight)
        if target <= accumulated:
            return price
    return in_band[-1][0]


@dataclass
class BookerStrategy:
    """``booker``: price drawn from a quantity-weighted histogram of the order book, within 1σ.

    Port of ``BookerSampler``. Both sides of the book feed one histogram, summing quantity at any
    shared price — exactly what the Java version's ``histogram.merge(price, quantity, ::add)`` does.

    Needs depth in ``MarketView.set_book``. The harness gets it from the broker's order-book snapshot
    relay, riding the *same* OFX envelope as the order, so it costs no extra round trip.
    """

    sigma: float = 1.0
    name: str = "booker"

    def decide(
        self, symbol: str, market: MarketView, portfolio: object, rng: random.Random
    ) -> OrderIntent | None:
        last = market.last_sale.get(symbol)
        if last is None:
            return None
        histogram: dict[Decimal, Decimal] = {}
        for _side, price, quantity in market.book.get(symbol, []):
            histogram[price] = histogram.get(price, Decimal(0)) + quantity
        price = histogram_sample(histogram, last, self.sigma, FALLBACK_BAND, rng)
        side, quantity = _side_and_quantity(rng)
        return OrderIntent(
            side=side, price=instruments.snap_to_tick(symbol, price), quantity=quantity
        )


@dataclass
class BookfishStrategy:
    """``bookfish``: price drawn from the traded-volume histogram, within 0.5σ — tighter than booker.

    Port of ``BookfishSampler``.

    **Documented divergence from Java.** The Java agent accumulates its histogram from fills observed
    on the FxcPub XMPP feed, so it sees the whole market. This harness has no XMPP client, so
    ``MarketView.record_trade`` accumulates only what *this user* observes — the broker's reported last
    sale per response. The distribution is therefore sparser and lags the market, and until a few
    trades have been seen the strategy behaves like ``rando`` via the fallback. Faithful in algorithm,
    narrower in input.
    """

    sigma: float = 0.5
    name: str = "bookfish"

    def decide(
        self, symbol: str, market: MarketView, portfolio: object, rng: random.Random
    ) -> OrderIntent | None:
        last = market.last_sale.get(symbol)
        if last is None:
            return None
        price = histogram_sample(
            market.traded_volume.get(symbol, {}), last, self.sigma, FALLBACK_BAND, rng
        )
        side, quantity = _side_and_quantity(rng)
        return OrderIntent(
            side=side, price=instruments.snap_to_tick(symbol, price), quantity=quantity
        )


#: Registry, mirroring ``Strategies.byName``.
_STRATEGIES = {
    "rando": RandoStrategy,
    "booker": BookerStrategy,
    "bookfish": BookfishStrategy,
}


def bare(name: str) -> Strategy:
    """Resolve the *unwrapped* sampler by name — no liquidity management.

    Used by tests that need to exercise a sampler's raw behaviour. Production callers want
    :func:`by_name`.
    """
    key = (name or "rando").lower()
    try:
        return _STRATEGIES[key]()
    except KeyError:
        raise ValueError(
            f"unknown strategy: {name} (available: {', '.join(sorted(_STRATEGIES))})"
        ) from None


def by_name(name: str) -> Strategy:
    """Resolve the *effective* strategy by name. Mirrors Java's ``Strategies.byName`` exactly.

    ``rando`` is the naive strategy and comes back bare — it ignores the portfolio, per story 001, and
    will eventually be rejected for insufficient cash or shares.

    ``booker`` and ``bookfish`` are non-naive and come back wrapped in
    :class:`~fxc_loadgen.liquidity.LiquidityPolicy`, which scales their buying to available cash and
    sells assets to maintain liquidity. That wrapping is what lets a run continue indefinitely rather
    than exhausting one side of the account.
    """
    strategy = bare(name)
    if strategy.name in NAIVE:
        return strategy
    # Imported here rather than at module scope: liquidity imports this module for its types.
    from .liquidity import LiquidityPolicy

    return LiquidityPolicy(strategy)
