"""Liquidity management for the non-naive strategies.

Twin of Java's ``LiquidityAwareStrategy`` — same three corrections, same ordering, same defaults. Kept
in step with it deliberately: ``booker`` must not mean two different things in two languages.

**Why.** The seeded demo accounts hold 1,000 shares and $1,000,000. A one-sided order stream exhausts
one or the other within minutes and then produces nothing but rejections, which is what prevented the
demo from running continuously. A naive strategy cannot know; the non-naive ones get told.

**What it does**, in order:

1. **Restore liquidity** — when cash has fallen below ``cash_floor_fraction`` of the cash first
   observed, force a SELL sized to bring it back to the floor.
2. **Cap buys by affordable cash** — only cash *above* the floor is spendable, and no single order may
   commit more than ``buy_budget_fraction`` of it, so buying can never itself breach the floor.
3. **Cap sells by holdings** — there is no shorting; the broker rejects a sell of more than is held, so
   the quantity is clamped rather than rejected.

In normal operation none of these bind: the samplers ask for 1–10 units, a rounding error against
$1,000,000. The policy is invisible until the account actually nears a limit, which is the intent — it
should change the outcome only where the naive version would have been rejected.

**Fails closed.** With no portfolio data it declines to trade rather than guessing.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from decimal import ROUND_CEILING, ROUND_DOWN, ROUND_FLOOR, Decimal

from . import instruments
from .strategies import MarketView, OrderIntent, Strategy

__all__ = ["Portfolio", "LiquidityPolicy", "DEFAULT_BUY_BUDGET_FRACTION", "DEFAULT_CASH_FLOOR_FRACTION"]

#: No single order may commit more than this share of spendable cash.
DEFAULT_BUY_BUDGET_FRACTION = Decimal("0.10")
#: Cash is kept at or above this share of the cash first observed.
DEFAULT_CASH_FLOOR_FRACTION = Decimal("0.25")


@dataclass
class Portfolio:
    """Holdings as last read from an OFX statement. Mirrors Java's ``PortfolioView``."""

    cash_by_currency: dict[str, Decimal] = field(default_factory=dict)
    shares: dict[str, Decimal] = field(default_factory=dict)

    def cash(self, currency: str) -> Decimal:
        return self.cash_by_currency.get(currency, Decimal(0))

    def held(self, symbol: str) -> Decimal:
        return self.shares.get(symbol, Decimal(0))

    def is_empty(self) -> bool:
        return not self.cash_by_currency and not self.shares


@dataclass
class LiquidityPolicy:
    """Wraps a strategy, making its order flow sustainable. Twin of ``LiquidityAwareStrategy``."""

    delegate: Strategy
    buy_budget_fraction: Decimal = DEFAULT_BUY_BUDGET_FRACTION
    cash_floor_fraction: Decimal = DEFAULT_CASH_FLOOR_FRACTION
    #: Cash per currency the first time it was seen — what the floor is measured against.
    _baseline_cash: dict[str, Decimal] = field(default_factory=dict, repr=False)

    @property
    def name(self) -> str:
        return self.delegate.name

    def decide(
        self, symbol: str, market: MarketView, portfolio: Portfolio | None, rng: random.Random
    ) -> OrderIntent | None:
        intent = self.delegate.decide(symbol, market, portfolio, rng)
        if intent is None:
            return None
        if portfolio is None or portfolio.is_empty():
            return None  # no holdings data — decline rather than guess
        try:
            instrument = instruments.find(symbol)
        except ValueError:
            return None
        if intent.price <= 0:
            return None

        cash = portfolio.cash(instrument.quote_currency)
        baseline = self._baseline_cash.setdefault(instrument.quote_currency, cash)
        floor = baseline * self.cash_floor_fraction
        sellable = self._sellable(instrument, symbol, portfolio)

        # 1. Below the floor: raise cash, whatever the sampler wanted.
        if cash < floor and sellable > 0:
            shortfall = floor - cash
            needed = (shortfall / intent.price).quantize(Decimal("1E-8"), rounding=ROUND_CEILING)
            # Round the requirement UP onto the lot grid, then clamp to holdings. Flooring here would
            # undershoot the floor by up to a lot and never quite restore liquidity; clamping second
            # keeps it inside what is actually held (no shorting).
            quantity = min(self._snap_up(instrument, needed), self._snap_down(instrument, sellable))
            if quantity <= 0:
                return None
            return OrderIntent(side="SELL", price=intent.price, quantity=quantity)

        # 2/3. Otherwise keep the sampler's side and only clamp the quantity.
        if intent.side == "BUY":
            spendable = max(Decimal(0), cash - floor) * self.buy_budget_fraction
            affordable = self._snap_down(
                instrument, (spendable / intent.price).quantize(Decimal("1E-8"), rounding=ROUND_DOWN)
            )
            quantity = min(intent.quantity, affordable)
            if quantity <= 0:
                return None  # cannot fund even one lot
            if quantity == intent.quantity:
                return intent
            return OrderIntent(side="BUY", price=intent.price, quantity=quantity)

        quantity = self._snap_down(instrument, min(intent.quantity, sellable))
        if quantity <= 0:
            return None  # nothing to sell — the broker would reject a short
        if quantity == intent.quantity:
            return intent
        return OrderIntent(side="SELL", price=intent.price, quantity=quantity)

    @staticmethod
    def _sellable(inst: instruments.Instrument, symbol: str, portfolio: Portfolio) -> Decimal:
        """Share position for an equity; base-currency balance for FX (a sell delivers base)."""
        if not inst.is_fx:
            return portfolio.held(symbol)
        base = symbol.split("/")[0]
        return portfolio.cash(base)

    @staticmethod
    def _snap_down(inst: instruments.Instrument, quantity: Decimal) -> Decimal:
        """Round down onto the lot grid — for anything bounded by cash or holdings."""
        if inst.lot_size <= 0:
            return quantity
        lots = (quantity / inst.lot_size).to_integral_value(rounding=ROUND_FLOOR)
        return lots * inst.lot_size

    @staticmethod
    def _snap_up(inst: instruments.Instrument, quantity: Decimal) -> Decimal:
        """Round up onto the lot grid — for a requirement that must actually be met."""
        if inst.lot_size <= 0:
            return quantity
        lots = (quantity / inst.lot_size).to_integral_value(rounding=ROUND_CEILING)
        return lots * inst.lot_size
