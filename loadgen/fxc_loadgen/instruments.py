"""The instrument universe, mirroring ``fxc-common``'s ``InstrumentCatalog``.

Tick and lot sizes matter to the harness: **FxcExchange rejects orders that violate them**
(``OrderValidation``), and that rejection arrives asynchronously over FIX — so the OFX reply still
says ``ROUTED`` and the order dies later. A mis-snapped price therefore produces load that looks
accepted but never trades.

Kept as a literal table rather than fetched, because there is no endpoint that serves it. That makes
it a duplication of ``InstrumentCatalog.defaults()``, so ``tests/test_instruments.py`` pins the values
and the tick-snapping arithmetic against the Java implementation's documented behaviour.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

__all__ = ["Instrument", "CATALOG", "EQUITIES", "find", "snap_to_tick", "snap_to_lot"]


@dataclass(frozen=True)
class Instrument:
    symbol: str
    tick_size: Decimal
    lot_size: Decimal
    quote_currency: str
    is_fx: bool


# Mirrors InstrumentCatalog.defaults(): four FX pairs (5-dp majors, JPY to 3 dp) and three equities.
CATALOG: dict[str, Instrument] = {
    inst.symbol: inst
    for inst in (
        Instrument("EUR/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
        Instrument("GBP/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
        Instrument("USD/JPY", Decimal("0.001"), Decimal("1000"), "JPY", True),
        Instrument("AUD/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
        Instrument("ACME", Decimal("0.01"), Decimal("1"), "USD", False),
        Instrument("GLOBEX", Decimal("0.01"), Decimal("1"), "USD", False),
        Instrument("INITECH", Decimal("0.01"), Decimal("1"), "USD", False),
    )
}

#: The equities the demo actually trades. FX pairs move two currency balances rather than a share
#: position, so the liquidity policy's "sell assets to restore cash" has no equivalent for them.
EQUITIES: tuple[str, ...] = ("ACME", "GLOBEX", "INITECH")


def find(symbol: str) -> Instrument:
    try:
        return CATALOG[symbol]
    except KeyError:
        raise ValueError(
            f"unknown instrument {symbol!r}; known: {', '.join(sorted(CATALOG))}"
        ) from None


def snap_to_tick(symbol: str, price: Decimal | float | str) -> Decimal:
    """Snap a raw price onto the instrument's tick grid.

    Mirrors ``InvestorAgent.snapToTick``:
    ``price.divide(tick, 0, HALF_UP).multiply(tick).setScale(tick.scale(), HALF_UP)`` — i.e. round to
    the nearest whole number of ticks, then carry the tick's own scale so the emitted decimal has
    exactly the expected number of places.
    """
    tick = find(symbol).tick_size
    value = Decimal(str(price)) if not isinstance(price, Decimal) else price
    ticks = (value / tick).quantize(Decimal(1), rounding=ROUND_HALF_UP)
    exponent = Decimal(1).scaleb(tick.as_tuple().exponent)  # tick.scale() equivalent
    return (ticks * tick).quantize(exponent, rounding=ROUND_HALF_UP)


def snap_to_lot(symbol: str, quantity: Decimal | float | int) -> Decimal:
    """Round a quantity **down** onto the instrument's lot grid.

    Down, not nearest: rounding up could exceed the cash or shares the caller verified it had, which
    the broker would then reject.
    """
    lot = find(symbol).lot_size
    value = Decimal(str(quantity)) if not isinstance(quantity, Decimal) else quantity
    lots = (value / lot).to_integral_value(rounding="ROUND_FLOOR")
    return lots * lot
