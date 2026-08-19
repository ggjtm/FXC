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
    reference_price: Decimal | None = None


# Mirrors InstrumentCatalog.LISTINGS: twenty-five fictitious equities, each with its own opening
# price. Prices matter as much as ticks here — seeding every book at one price put a rando's orders
# hundreds of percent away from the book on the expensive names, so they never crossed.
_EQUITIES: tuple[Instrument, ...] = (
    Instrument("ARVX", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("18.25")),
    Instrument("BLTN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("9.60")),
    Instrument("CRVN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("46.75")),
    Instrument("DYNL", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("132.40")),
    Instrument("ELXR", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("74.15")),
    Instrument("FRTH", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("31.05")),
    Instrument("GRVT", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("208.60")),
    Instrument("HLYN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("22.90")),
    Instrument("IVRN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("63.30")),
    Instrument("JNTR", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("15.45")),
    Instrument("KLSO", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("38.70")),
    Instrument("LUMR", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("87.25")),
    Instrument("MRDN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("26.15")),
    Instrument("NVSK", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("154.80")),
    Instrument("ORBN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("96.50")),
    Instrument("PLTH", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("11.35")),
    Instrument("QRVN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("41.60")),
    Instrument("RDSN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("58.95")),
    Instrument("STLR", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("19.80")),
    Instrument("TRQL", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("33.20")),
    Instrument("UPLN", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("112.05")),
    Instrument("VNTA", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("68.40")),
    Instrument("WSTB", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("14.70")),
    Instrument("XNTH", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("245.30")),
    Instrument("YRRA", Decimal("0.01"), Decimal("1"), "USD", False, Decimal("29.55")),
)

# NO LONGER LISTED — the exchange rejects these (fxc/docs/PROBLEMS.md P23). They stay in CATALOG
# because ofx.security_id's "FX:" branch is still live code on both sides of the wire and the golden
# fixture sample_data/ofx-order-eurusd.xml pins it. They are absent from EQUITIES, which is what
# "the tradable universe" means here.
_FX: tuple[Instrument, ...] = (
    Instrument("EUR/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
    Instrument("GBP/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
    Instrument("USD/JPY", Decimal("0.001"), Decimal("1000"), "JPY", True),
    Instrument("AUD/USD", Decimal("0.00001"), Decimal("1000"), "USD", True),
)

CATALOG: dict[str, Instrument] = {inst.symbol: inst for inst in (*_EQUITIES, *_FX)}

#: The equities the demo actually trades, in listing order. An empty ``--symbols`` resolves to
#: exactly this, which is what keeps the 25-name list out of the Salt pillar, the systemd unit and
#: docker-compose. FX pairs move two currency balances rather than a share position, so the
#: liquidity policy's "sell assets to restore cash" has no equivalent for them.
EQUITIES: tuple[str, ...] = tuple(inst.symbol for inst in _EQUITIES)


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
