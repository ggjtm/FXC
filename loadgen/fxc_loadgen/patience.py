"""Patience for ``bookfish``: wait for an advantage instead of always submitting (story 003).

Twin of Java's ``strategy.PatientStrategy`` — same two gates, same defaults, same ordering. Kept in
step with it deliberately, for the reason ``liquidity.py`` gives: a strategy must not mean two
different things in two languages.

**Why.** ``bookfish`` samples a price from the histogram of volume actually traded. When that
histogram is thin, ``histogram_sample`` falls back to a uniform ±1% draw around the last sale — which
is ``rando`` wearing ``bookfish``'s name, and indistinguishable from it in the stats table. A strategy
whose whole premise is "trade where volume trades" should **decline** in that situation, not guess.

**What it does**, in order:

1. **Readiness.** The traded-volume histogram must support a distribution at all (two or more
   positive-weight bins with non-zero spread — the cases :func:`~fxc_loadgen.strategies.histogram_stats`
   returns ``None`` for) *and* carry at least ``min_volume`` of observed volume. Otherwise: abstain,
   reason ``not-ready``.
2. **Edge.** Fair value is the volume-weighted mean price of that histogram — the same centre the
   sampler draws around, which is why both read it from ``histogram_stats``. The drawn target has to
   sit at least ``min_edge_ticks`` on the *favourable* side of fair value for the drawn side: below it
   to buy, above it to sell. Otherwise: abstain, reason ``no-edge``.

Three consequences worth knowing:

* **The draw happens first, always.** The delegate samples price *and* side/quantity before either
  gate is consulted, so abstaining consumes exactly the same random numbers as trading. Whether
  ``bookfish`` is patient therefore cannot shift the rest of a seeded sequence.
* **Roughly half of all ticks abstain.** For a symmetric histogram, a coin-flipped side agrees with
  the sampled price's direction about half the time. That is the patience, and the
  ``BOOKFISH skipped:no-edge`` row is what it looks like from the outside.
* **It is stateless.** No cooldown, no memory of past abstentions: the decision is re-made every tick
  against fresh data. That is what keeps patience from starving the liquidity policy (which wraps
  *this*, and declines whenever its delegate declines) — a forced liquidity sell is delayed by at most
  one tick's worth of abstention, never prevented.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from decimal import Decimal

from . import instruments
from .strategies import MarketView, OrderIntent, Strategy, histogram_stats

__all__ = ["PatienceGate", "gate_of", "DEFAULT_MIN_VOLUME", "DEFAULT_MIN_EDGE_TICKS"]

#: Observed traded volume required before the histogram is trusted at all. Low enough that the
#: exchange's volume-by-price feed clears it within a few seconds of a live market, high enough that a
#: handful of self-observed fills does not read as a distribution.
DEFAULT_MIN_VOLUME = Decimal("50")

#: How far onto the favourable side of fair value a target must sit, in instrument ticks. One tick is
#: the smallest advantage the market can actually express; ``0`` disables the gate.
DEFAULT_MIN_EDGE_TICKS = 1

#: Reasons an abstention can carry. Surfaced by the harness as ``BOOKFISH skipped:<reason>`` so a quiet
#: strategy can be told apart from a broken one.
NOT_READY = "not-ready"
NO_EDGE = "no-edge"
NO_OPINION = "no-opinion"


@dataclass
class PatienceGate:
    """Wraps a strategy so it only submits when it can name an advantage."""

    delegate: Strategy
    min_volume: Decimal = DEFAULT_MIN_VOLUME
    min_edge_ticks: int = DEFAULT_MIN_EDGE_TICKS
    #: Why the last call abstained, or ``None`` if it traded. Read by the harness for the stats row;
    #: per-user instance, so this is not shared state.
    last_reason: str | None = field(default=None, repr=False)

    @property
    def name(self) -> str:
        return self.delegate.name

    def decide(
        self, symbol: str, market: MarketView, portfolio: object, rng: random.Random
    ) -> OrderIntent | None:
        # Delegate first, unconditionally: the gates must not change what is drawn, only what is sent.
        intent = self.delegate.decide(symbol, market, portfolio, rng)
        if intent is None:
            self.last_reason = NO_OPINION
            return None

        stats = histogram_stats(market.traded_volume.get(symbol, {}))
        if stats is None or stats.total_weight < float(self.min_volume):
            self.last_reason = NOT_READY
            return None

        if self.min_edge_ticks > 0:
            try:
                tick = instruments.find(symbol).tick_size
            except ValueError:
                # An instrument we cannot price the edge in tick terms for; decline rather than
                # invent a margin.
                self.last_reason = NOT_READY
                return None
            margin = tick * self.min_edge_ticks
            fair_value = Decimal(str(stats.mean))
            edge = fair_value - intent.price if intent.side == "BUY" else intent.price - fair_value
            if edge < margin:
                self.last_reason = NO_EDGE
                return None

        self.last_reason = None
        return intent


def gate_of(strategy: object) -> PatienceGate | None:
    """Find the patience gate inside a wrapped strategy, or ``None`` if there isn't one.

    ``strategies.by_name`` returns decorators around decorators, and the harness needs the gate itself
    to read :attr:`PatienceGate.last_reason` for the stats row. Walking the ``delegate`` chain keeps
    that knowledge here rather than hardcoding the wrapping order in ``locustfile.py``.
    """
    seen = 0
    while strategy is not None and seen < 8:  # the chain is two deep; the bound is paranoia
        if isinstance(strategy, PatienceGate):
            return strategy
        strategy = getattr(strategy, "delegate", None)
        seen += 1
    return None
