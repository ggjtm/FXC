"""``booker`` and ``bookfish`` ports, and the histogram sampler they share.

Mirrors what the Java ``HistogramSamplerTest`` establishes: a weight-weighted draw restricted to
``sigma`` standard deviations of the last sale, with a uniform ±1% fallback whenever the histogram
cannot support a distribution.

Determinism note: this asserts reproducibility *within* Python. Java and Python cannot produce the
same sequence from the same seed — Java's ``Random`` is a 48-bit LCG, Python's is a Mersenne Twister —
so cross-language sequence equality is not a goal and is not tested. What is shared is the algorithm
and its bounds.
"""

from __future__ import annotations

import random
import unittest
from decimal import Decimal

from fxc_loadgen import strategies


def _book(*levels: tuple[str, str, str]) -> list[tuple[str, Decimal, Decimal]]:
    return [(side, Decimal(price), Decimal(size)) for side, price, size in levels]


class HistogramSampleTest(unittest.TestCase):
    """The shared sampler, driven directly."""

    def test_single_bin_falls_back(self):
        # Fewer than two positive-weight bins: no distribution.
        histogram = {Decimal("42.10"): Decimal("100")}
        price = strategies.histogram_sample(histogram, Decimal("42.10"), 1.0, 0.01, random.Random(1))
        self.assertNotEqual(Decimal("42.10"), price)  # a fallback draw, not the bin
        self.assertLess(abs(price - Decimal("42.10")), Decimal("42.10") * Decimal("0.02"))

    def test_empty_histogram_falls_back(self):
        price = strategies.histogram_sample({}, Decimal("42.10"), 1.0, 0.01, random.Random(1))
        self.assertLess(abs(price - Decimal("42.10")), Decimal("42.10") * Decimal("0.02"))

    def test_zero_and_negative_weights_are_ignored(self):
        histogram = {
            Decimal("42.00"): Decimal("0"),
            Decimal("42.10"): Decimal("-5"),
            Decimal("42.20"): Decimal("100"),
        }
        # Only one positive bin survives -> fallback.
        price = strategies.histogram_sample(histogram, Decimal("42.10"), 1.0, 0.01, random.Random(3))
        self.assertNotIn(price, set(histogram))

    def test_all_weight_at_one_price_falls_back_on_zero_variance(self):
        # Two bins but identical prices cannot happen in a dict; simulate zero variance with
        # equal prices via a single key and confirm the <2-bin path. Covered above; here assert the
        # std<=0 guard with two bins whose prices are equal after float conversion.
        tiny = Decimal("1E-30")
        histogram = {Decimal("42.10"): Decimal("100"), Decimal("42.10") + tiny: Decimal("100")}
        price = strategies.histogram_sample(histogram, Decimal("42.10"), 1.0, 0.01, random.Random(5))
        self.assertIsInstance(price, Decimal)  # must not raise or divide by zero

    def test_draws_only_from_bins_inside_the_band(self):
        # Prices spread wide; a tight sigma must exclude the far bins.
        histogram = {
            Decimal("10.00"): Decimal("100"),
            Decimal("42.00"): Decimal("100"),
            Decimal("42.10"): Decimal("100"),
            Decimal("42.20"): Decimal("100"),
            Decimal("90.00"): Decimal("100"),
        }
        drawn = {
            strategies.histogram_sample(histogram, Decimal("42.10"), 0.05, 0.01, random.Random(s))
            for s in range(200)
        }
        # With a very tight band only the near cluster is reachable.
        self.assertTrue(drawn.issubset({Decimal("42.00"), Decimal("42.10"), Decimal("42.20")}), drawn)

    def test_weighting_biases_the_draw(self):
        # A lopsided histogram makes the weighted σ tiny (here ~0.006), so the band has to be a large
        # multiple of it for both bins to be reachable at all — otherwise every draw takes the
        # fallback and the weighting is never exercised.
        histogram = {Decimal("42.00"): Decimal("1"), Decimal("42.20"): Decimal("999")}
        counts = {Decimal("42.00"): 0, Decimal("42.20"): 0}
        fallbacks = 0
        for seed in range(400):
            price = strategies.histogram_sample(
                histogram, Decimal("42.10"), 50.0, 0.01, random.Random(seed)
            )
            if price in counts:
                counts[price] += 1
            else:
                fallbacks += 1
        self.assertEqual(0, fallbacks, "band should be wide enough to include both bins")
        self.assertGreater(counts[Decimal("42.20")], counts[Decimal("42.00")] * 10, counts)

    def test_returns_an_exact_bin_price_so_it_is_already_tick_aligned(self):
        histogram = {Decimal("42.00"): Decimal("100"), Decimal("42.20"): Decimal("100")}
        for seed in range(50):
            price = strategies.histogram_sample(
                histogram, Decimal("42.10"), 10.0, 0.01, random.Random(seed)
            )
            self.assertIn(price, histogram)

    def test_consumes_one_draw_on_both_paths(self):
        # Which branch is taken must not shift the rest of the sequence, or side/quantity would
        # diverge between a populated and an empty book.
        usable = {Decimal("42.00"): Decimal("100"), Decimal("42.20"): Decimal("100")}

        rng_a = random.Random(11)
        strategies.histogram_sample(usable, Decimal("42.10"), 10.0, 0.01, rng_a)
        after_histogram = rng_a.random()

        rng_b = random.Random(11)
        strategies.histogram_sample({}, Decimal("42.10"), 10.0, 0.01, rng_b)
        after_fallback = rng_b.random()

        self.assertEqual(after_histogram, after_fallback)


class BookerStrategyTest(unittest.TestCase):
    def _market(self) -> strategies.MarketView:
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        market.set_book(
            "ACME",
            _book(
                ("BID", "42.09", "300"),
                ("BID", "42.08", "500"),
                ("OFFER", "42.11", "200"),
                ("OFFER", "42.12", "400"),
            ),
        )
        return market

    def test_returns_none_without_a_last_sale(self):
        market = strategies.MarketView()
        market.set_book("ACME", _book(("BID", "42.09", "300")))
        self.assertIsNone(
            strategies.BookerStrategy().decide("ACME", market, None, random.Random(1))
        )

    def test_draws_from_book_prices(self):
        strategy = strategies.BookerStrategy()
        market = self._market()
        book_prices = {Decimal("42.09"), Decimal("42.08"), Decimal("42.11"), Decimal("42.12")}
        drawn = {strategy.decide("ACME", market, None, random.Random(s)).price for s in range(100)}
        self.assertTrue(drawn.issubset(book_prices), f"unexpected prices: {drawn - book_prices}")

    def test_both_sides_of_the_book_feed_one_histogram(self):
        # The Java version merges bid and offer quantities into a single histogram.
        strategy = strategies.BookerStrategy()
        market = self._market()
        drawn = {strategy.decide("ACME", market, None, random.Random(s)).price for s in range(200)}
        self.assertTrue(any(p < Decimal("42.10") for p in drawn), "should reach bid side")
        self.assertTrue(any(p > Decimal("42.10") for p in drawn), "should reach offer side")

    def test_quantities_at_a_shared_price_are_summed(self):
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        # Same price on both sides — pathological but the merge must not lose a level.
        market.set_book("ACME", _book(("BID", "42.10", "100"), ("OFFER", "42.10", "100"),
                                      ("OFFER", "42.20", "1")))
        strategy = strategies.BookerStrategy()
        drawn = {strategy.decide("ACME", market, None, random.Random(s)).price for s in range(100)}
        # 42.10 carries 200 of 201 weight, so it must dominate.
        self.assertIn(Decimal("42.10"), drawn)

    def test_empty_book_falls_back_to_a_rando_like_draw(self):
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        strategy = strategies.BookerStrategy()
        for seed in range(50):
            intent = strategy.decide("ACME", market, None, random.Random(seed))
            self.assertLess(abs(intent.price - Decimal("42.10")), Decimal("0.90"))

    def test_prices_are_tick_snapped_even_on_the_fallback_path(self):
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        strategy = strategies.BookerStrategy()
        for seed in range(50):
            intent = strategy.decide("ACME", market, None, random.Random(seed))
            self.assertEqual(Decimal(0), intent.price % Decimal("0.01"), intent.price)

    def test_side_and_quantity_bounds_are_shared_with_rando(self):
        strategy = strategies.BookerStrategy()
        market = self._market()
        sides, quantities = set(), set()
        for seed in range(200):
            intent = strategy.decide("ACME", market, None, random.Random(seed))
            sides.add(intent.side)
            quantities.add(int(intent.quantity))
        self.assertEqual({"BUY", "SELL"}, sides)
        self.assertTrue(quantities <= set(range(1, 11)), quantities)

    def test_sigma_is_one(self):
        self.assertEqual(1.0, strategies.BookerStrategy().sigma)

    def test_reproducible(self):
        strategy = strategies.BookerStrategy()
        market = self._market()

        def run():
            rng = random.Random(42)
            return [strategy.decide("ACME", market, None, rng) for _ in range(20)]

        self.assertEqual(run(), run())


class BookfishStrategyTest(unittest.TestCase):
    def _market(self) -> strategies.MarketView:
        market = strategies.MarketView()
        for price, qty in (("42.05", "10"), ("42.10", "50"), ("42.15", "30")):
            market.record_trade("ACME", Decimal(price), Decimal(qty))
        market.set_last_sale("ACME", Decimal("42.10"))
        return market

    def test_returns_none_without_a_last_sale(self):
        self.assertIsNone(
            strategies.BookfishStrategy().decide(
                "ACME", strategies.MarketView(), None, random.Random(1)
            )
        )

    def test_draws_from_traded_prices(self):
        strategy = strategies.BookfishStrategy()
        market = self._market()
        traded = {Decimal("42.05"), Decimal("42.10"), Decimal("42.15")}
        drawn = {strategy.decide("ACME", market, None, random.Random(s)).price for s in range(100)}
        self.assertTrue(drawn.issubset(traded), f"unexpected: {drawn - traded}")

    def test_sigma_is_tighter_than_booker(self):
        # 0.5σ vs booker's 1.0σ — story 003's defining difference.
        self.assertEqual(0.5, strategies.BookfishStrategy().sigma)
        self.assertLess(
            strategies.BookfishStrategy().sigma, strategies.BookerStrategy().sigma
        )

    def test_no_trades_yet_behaves_like_rando(self):
        # The documented consequence of having no XMPP feed: until trades are observed, the
        # histogram is empty and every draw takes the ±1% fallback.
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        strategy = strategies.BookfishStrategy()
        for seed in range(50):
            intent = strategy.decide("ACME", market, None, random.Random(seed))
            self.assertLess(abs(intent.price - Decimal("42.10")), Decimal("0.90"))

    def test_accumulates_its_own_observations(self):
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        strategy = strategies.BookfishStrategy()
        # Before observing anything: fallback territory.
        self.assertEqual({}, market.traded_volume)
        # After observing two prices the histogram can support a draw.
        market.record_trade("ACME", Decimal("42.00"), Decimal("10"))
        market.record_trade("ACME", Decimal("42.20"), Decimal("10"))
        market.set_last_sale("ACME", Decimal("42.10"))
        drawn = {strategy.decide("ACME", market, None, random.Random(s)).price for s in range(100)}
        self.assertTrue(drawn & {Decimal("42.00"), Decimal("42.20")}, drawn)

    def test_reproducible(self):
        strategy = strategies.BookfishStrategy()
        market = self._market()

        def run():
            rng = random.Random(3)
            return [strategy.decide("ACME", market, None, rng) for _ in range(20)]

        self.assertEqual(run(), run())


class ResolutionTest(unittest.TestCase):
    def test_all_three_resolve(self):
        for name in ("rando", "booker", "bookfish"):
            self.assertEqual(name, strategies.by_name(name).name)

    def test_case_insensitive(self):
        self.assertEqual("bookfish", strategies.by_name("BookFish").name)

    def test_unknown_lists_all_three(self):
        with self.assertRaises(ValueError) as caught:
            strategies.by_name("nope")
        message = str(caught.exception)
        for name in ("rando", "booker", "bookfish"):
            self.assertIn(name, message)

    def test_only_rando_is_naive(self):
        self.assertEqual({"rando"}, set(strategies.NAIVE))


if __name__ == "__main__":
    unittest.main()
