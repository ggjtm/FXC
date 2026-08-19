"""Strategy ports. The contract is: same shape and same bounds as the Java originals, and
deterministic under a seeded RNG so a run can be reproduced.
"""

from __future__ import annotations

import random
import unittest
from decimal import Decimal

from fxc_loadgen import instruments, strategies


class MarketViewTest(unittest.TestCase):
    def test_record_trade_updates_last_sale_and_volume_histogram(self):
        market = strategies.MarketView()
        market.record_trade("ARVX", Decimal("42.10"), Decimal("5"))
        market.record_trade("ARVX", Decimal("42.10"), Decimal("3"))
        market.record_trade("ARVX", Decimal("42.20"), Decimal("2"))

        self.assertEqual(Decimal("42.20"), market.last_sale["ARVX"])
        # Volume accumulates per price — the histogram bookfish samples from.
        self.assertEqual(Decimal("8"), market.traded_volume["ARVX"][Decimal("42.10")])
        self.assertEqual(Decimal("2"), market.traded_volume["ARVX"][Decimal("42.20")])


class RandoStrategyTest(unittest.TestCase):
    def _market(self) -> strategies.MarketView:
        market = strategies.MarketView()
        market.set_last_sale("ARVX", Decimal("42.10"))
        return market

    def test_returns_none_without_a_last_sale(self):
        # Java: RandoSampler returns Optional.empty(), logged as SKIPPED.
        strategy = strategies.RandoStrategy()
        self.assertIsNone(strategy.decide("ARVX", strategies.MarketView(), None, random.Random(1)))

    def test_price_stays_within_the_one_percent_band(self):
        strategy = strategies.RandoStrategy()
        market = self._market()
        last = Decimal("42.10")
        lo, hi = last * Decimal("0.99"), last * Decimal("1.01")
        for seed in range(200):
            intent = strategy.decide("ARVX", market, None, random.Random(seed))
            self.assertIsNotNone(intent)
            # Snapping to a cent can nudge one tick outside the raw band; allow exactly that.
            tick = instruments.find("ARVX").tick_size
            self.assertGreaterEqual(intent.price, lo - tick, f"seed {seed}")
            self.assertLessEqual(intent.price, hi + tick, f"seed {seed}")

    def test_quantity_bounds_match_the_java_sampling_strategy(self):
        strategy = strategies.RandoStrategy()
        market = self._market()
        seen = set()
        for seed in range(500):
            intent = strategy.decide("ARVX", market, None, random.Random(seed))
            self.assertGreaterEqual(intent.quantity, Decimal(strategies.MIN_QTY))
            self.assertLessEqual(intent.quantity, Decimal(strategies.MAX_QTY))
            seen.add(int(intent.quantity))
        self.assertEqual(set(range(1, 11)), seen, "should span the full 1..10 range")

    def test_both_sides_occur(self):
        strategy = strategies.RandoStrategy()
        market = self._market()
        sides = {strategy.decide("ARVX", market, None, random.Random(s)).side for s in range(50)}
        self.assertEqual({"BUY", "SELL"}, sides)

    def test_prices_are_tick_snapped(self):
        strategy = strategies.RandoStrategy()
        market = self._market()
        for seed in range(100):
            intent = strategy.decide("ARVX", market, None, random.Random(seed))
            # A cent grid: exactly two decimal places, and an exact multiple of the tick.
            self.assertEqual(2, -intent.price.as_tuple().exponent)
            self.assertEqual(Decimal(0), intent.price % Decimal("0.01"))

    def test_deterministic_under_a_seed(self):
        strategy = strategies.RandoStrategy()
        market = self._market()
        first = [strategy.decide("ARVX", market, None, r) for r in [random.Random(7)] * 1]
        second = [strategy.decide("ARVX", market, None, r) for r in [random.Random(7)] * 1]
        self.assertEqual(first, second)

    def test_a_sequence_from_one_rng_is_reproducible(self):
        strategy = strategies.RandoStrategy()
        market = self._market()

        def run() -> list[strategies.OrderIntent]:
            rng = random.Random(99)
            return [strategy.decide("ARVX", market, None, rng) for _ in range(20)]

        self.assertEqual(run(), run())

    def test_band_is_configurable(self):
        wide = strategies.RandoStrategy(band=0.5)
        market = self._market()
        prices = {wide.decide("ARVX", market, None, random.Random(s)).price for s in range(100)}
        # A 50% band must reach well outside the 1% one.
        self.assertTrue(any(p < Decimal("41") or p > Decimal("43") for p in prices))


class ResolutionTest(unittest.TestCase):
    def test_by_name_is_case_insensitive(self):
        self.assertEqual("rando", strategies.by_name("RANDO").name)
        self.assertEqual("rando", strategies.by_name("rando").name)

    def test_none_defaults_to_rando(self):
        # Mirrors Strategies.byName's null handling.
        self.assertEqual("rando", strategies.by_name(None).name)

    def test_unknown_raises_and_lists_alternatives(self):
        with self.assertRaises(ValueError) as caught:
            strategies.by_name("nope")
        self.assertIn("rando", str(caught.exception))

    def test_rando_is_declared_naive(self):
        # The liquidity policy is applied to the non-naive strategies only.
        self.assertIn("rando", strategies.NAIVE)


class RandoTouchTest(unittest.TestCase):
    """``rando`` takes the touch when there is one — and must survive a book that has only one side.

    A long-only market empties a side routinely: with cash-only investors (stories/004) nobody can sell
    until they have bought, so the offer side can be empty for long stretches. Asking for the best price
    on an empty side is what ``min()``/``max()`` raise on, and a strategy that raises does not skip a
    tick — it kills the locust task and the investor places nothing.
    """

    def _market(self, levels):
        market = strategies.MarketView()
        market.set_last_sale("ARVX", Decimal("42.10"))
        if levels is not None:
            market.set_book("ARVX", levels)
        return market

    def test_buys_the_best_offer(self):
        market = self._market([("OFFER", Decimal("42.20"), Decimal("10")),
                               ("OFFER", Decimal("42.15"), Decimal("10")),
                               ("BID", Decimal("42.00"), Decimal("10"))])
        prices = {strategies.RandoStrategy().decide("ARVX", market, None, random.Random(s)).price
                  for s in range(40)
                  if strategies.RandoStrategy().decide("ARVX", market, None, random.Random(s)).side == "BUY"}
        self.assertEqual({Decimal("42.15")}, prices, "a buy lifts the best offer")

    def test_sells_the_best_bid(self):
        market = self._market([("BID", Decimal("42.00"), Decimal("10")),
                               ("BID", Decimal("42.05"), Decimal("10")),
                               ("OFFER", Decimal("42.20"), Decimal("10"))])
        prices = {strategies.RandoStrategy().decide("ARVX", market, None, random.Random(s)).price
                  for s in range(40)
                  if strategies.RandoStrategy().decide("ARVX", market, None, random.Random(s)).side == "SELL"}
        self.assertEqual({Decimal("42.05")}, prices, "a sell hits the best bid")

    def test_an_empty_side_falls_back_instead_of_raising(self):
        # The state a long-only market spends most of its time in.
        bids_only = self._market([("BID", Decimal("42.00"), Decimal("10"))])
        offers_only = self._market([("OFFER", Decimal("42.20"), Decimal("10"))])
        for market in (bids_only, offers_only, self._market([]), self._market(None)):
            for seed in range(30):
                intent = strategies.RandoStrategy().decide("ARVX", market, None, random.Random(seed))
                self.assertIsNotNone(intent)
                self.assertLess(abs(intent.price - Decimal("42.10")), Decimal("0.90"))

    def test_a_book_for_another_symbol_is_not_a_book_for_this_one(self):
        market = strategies.MarketView()
        market.set_last_sale("ARVX", Decimal("42.10"))
        market.set_book("BLTN", [("OFFER", Decimal("11.00"), Decimal("5"))])
        intent = strategies.RandoStrategy().decide("ARVX", market, None, random.Random(1))
        self.assertLess(abs(intent.price - Decimal("42.10")), Decimal("0.90"))

    def test_every_price_is_on_the_tick_grid(self):
        # Off-tick prices are rejected by the exchange asynchronously, after the OFX reply said ROUTED.
        market = self._market([("OFFER", Decimal("42.20"), Decimal("10")),
                               ("BID", Decimal("42.00"), Decimal("10"))])
        for seed in range(60):
            for m in (market, self._market([])):
                price = strategies.RandoStrategy().decide("ARVX", m, None, random.Random(seed)).price
                self.assertEqual(Decimal(0), price % Decimal("0.01"), price)



if __name__ == "__main__":
    unittest.main()
