"""Instrument table and tick/lot snapping.

These matter because FxcExchange rejects tick/lot violations, and it does so **asynchronously over
FIX** — the OFX reply still says ``ROUTED``, so a mis-snapped price produces load that looks accepted
and never trades. The table also duplicates ``InstrumentCatalog.defaults()``, so the values are pinned
here to make drift a test failure rather than a silent one.
"""

from __future__ import annotations

import unittest
from decimal import Decimal

from fxc_loadgen import instruments


class CatalogTest(unittest.TestCase):
    def test_mirrors_the_java_catalog(self):
        # Exactly the seven instruments InstrumentCatalog.defaults() lists.
        self.assertEqual(
            {"EUR/USD", "GBP/USD", "USD/JPY", "AUD/USD", "ACME", "GLOBEX", "INITECH"},
            set(instruments.CATALOG),
        )

    def test_tick_and_lot_sizes(self):
        # 5-dp majors, JPY to 3 dp, equities to a cent with a lot of 1.
        self.assertEqual(Decimal("0.00001"), instruments.find("EUR/USD").tick_size)
        self.assertEqual(Decimal("0.001"), instruments.find("USD/JPY").tick_size)
        self.assertEqual(Decimal("1000"), instruments.find("AUD/USD").lot_size)
        self.assertEqual(Decimal("0.01"), instruments.find("ACME").tick_size)
        self.assertEqual(Decimal("1"), instruments.find("ACME").lot_size)

    def test_fx_flag_and_quote_currency(self):
        self.assertTrue(instruments.find("USD/JPY").is_fx)
        self.assertEqual("JPY", instruments.find("USD/JPY").quote_currency)
        self.assertFalse(instruments.find("ACME").is_fx)
        self.assertEqual("USD", instruments.find("ACME").quote_currency)

    def test_unknown_symbol_names_the_alternatives(self):
        with self.assertRaises(ValueError) as caught:
            instruments.find("NOPE")
        self.assertIn("ACME", str(caught.exception))


class SnapToTickTest(unittest.TestCase):
    def test_equity_snaps_to_a_cent_and_carries_the_tick_scale(self):
        self.assertEqual(Decimal("42.10"), instruments.snap_to_tick("ACME", "42.104"))
        self.assertEqual(Decimal("42.11"), instruments.snap_to_tick("ACME", "42.105"))  # HALF_UP
        # The result carries the tick's scale, so the emitted decimal has the expected places.
        self.assertEqual(2, -instruments.snap_to_tick("ACME", "42.1").as_tuple().exponent)

    def test_fx_major_snaps_to_five_places(self):
        self.assertEqual(Decimal("1.08420"), instruments.snap_to_tick("EUR/USD", "1.084204"))
        self.assertEqual(5, -instruments.snap_to_tick("EUR/USD", "1.0842").as_tuple().exponent)

    def test_jpy_snaps_to_three_places(self):
        self.assertEqual(Decimal("156.250"), instruments.snap_to_tick("USD/JPY", "156.2504"))
        self.assertEqual(3, -instruments.snap_to_tick("USD/JPY", "156.25").as_tuple().exponent)

    def test_snapped_prices_survive_the_ofx_number_format(self):
        # The snapped value must also be emittable: java_double refuses exponent notation.
        from fxc_loadgen import ofx

        for symbol, raw in (("ACME", "42.104"), ("EUR/USD", "1.084204"), ("USD/JPY", "156.2504")):
            self.assertNotIn("e", ofx.java_double(instruments.snap_to_tick(symbol, raw)))


class SnapToLotTest(unittest.TestCase):
    def test_equity_lot_is_one(self):
        self.assertEqual(Decimal("7"), instruments.snap_to_lot("ACME", "7.9"))

    def test_fx_rounds_down_to_the_lot(self):
        # Down, never up: rounding up could exceed the cash the caller verified it had.
        self.assertEqual(Decimal("1000"), instruments.snap_to_lot("EUR/USD", "1999"))
        self.assertEqual(Decimal("0"), instruments.snap_to_lot("EUR/USD", "999"))

    def test_zero_and_negative(self):
        self.assertEqual(Decimal("0"), instruments.snap_to_lot("ACME", "0"))
        self.assertEqual(Decimal("-1"), instruments.snap_to_lot("ACME", "-0.5"))


if __name__ == "__main__":
    unittest.main()
