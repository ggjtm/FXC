"""``bookfish``'s patience gate (FxcInvestor/docs/stories/003).

The behaviour under test is an *abstention*, which is easy to get wrong in a way nothing complains
about: too strict and the strategy never trades while the stats table quietly fills with skips; too
loose and it is the uniform fallback again, i.e. ``rando`` under another name. So these tests pin both
directions — that it waits, and that it eventually acts.

Mirrored by Java's ``PatientStrategyTest``.
"""

from __future__ import annotations

import random
import unittest
from decimal import Decimal

from fxc_loadgen import liquidity, patience, strategies


def _market(*trades: tuple[str, str], last: str = "42.10") -> strategies.MarketView:
    market = strategies.MarketView()
    for price, quantity in trades:
        market.record_trade("ACME", Decimal(price), Decimal(quantity))
    market.set_last_sale("ACME", Decimal(last))
    return market


class _Fixed:
    """A delegate with no randomness, so a test can aim a single intent at the gates."""

    name = "bookfish"

    def __init__(self, intent: strategies.OrderIntent | None, draws: int = 0):
        self.intent = intent
        self.draws = draws

    def decide(self, symbol, market, portfolio, rng):
        for _ in range(self.draws):
            rng.random()
        return self.intent


def _intent(side: str, price: str, quantity: str = "5") -> strategies.OrderIntent:
    return strategies.OrderIntent(side=side, price=Decimal(price), quantity=Decimal(quantity))


class ReadinessTest(unittest.TestCase):
    def test_abstains_with_no_observations_at_all(self):
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        self.assertIsNone(gate.decide("ACME", _market(), None, random.Random(1)))
        self.assertEqual(patience.NOT_READY, gate.last_reason)

    def test_abstains_on_a_single_price_bin(self):
        # One bin cannot express a distribution; this is the case the sampler papers over.
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        self.assertIsNone(
            gate.decide("ACME", _market(("42.10", "5000")), None, random.Random(1))
        )
        self.assertEqual(patience.NOT_READY, gate.last_reason)

    def test_abstains_below_the_volume_threshold(self):
        market = _market(("42.00", "3"), ("42.20", "4"))  # 7 total, threshold is 50
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        self.assertIsNone(gate.decide("ACME", market, None, random.Random(1)))
        self.assertEqual(patience.NOT_READY, gate.last_reason)

    def test_trades_once_the_threshold_is_met(self):
        market = _market(("42.00", "40"), ("42.20", "40"))  # 80 total, mean 42.10
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        self.assertIsNotNone(gate.decide("ACME", market, None, random.Random(1)))
        self.assertIsNone(gate.last_reason)

    def test_threshold_is_configurable(self):
        market = _market(("42.00", "3"), ("42.20", "4"))
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")), min_volume=Decimal("5"))
        self.assertIsNotNone(gate.decide("ACME", market, None, random.Random(1)))

    def test_a_delegate_with_no_opinion_is_reported_separately(self):
        # No last sale: the sampler declines before patience is even relevant, and the reason has to
        # say so rather than blaming the histogram.
        gate = patience.PatienceGate(_Fixed(None))
        self.assertIsNone(gate.decide("ACME", _market(), None, random.Random(1)))
        self.assertEqual(patience.NO_OPINION, gate.last_reason)

    def test_unknown_instrument_declines_rather_than_inventing_a_margin(self):
        market = strategies.MarketView()
        for price, quantity in (("1.10", "40"), ("1.30", "40")):
            market.record_trade("NOPE", Decimal(price), Decimal(quantity))
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "1.10")))
        self.assertIsNone(gate.decide("NOPE", market, None, random.Random(1)))
        self.assertEqual(patience.NOT_READY, gate.last_reason)


class EdgeTest(unittest.TestCase):
    #: mean = 42.10 exactly, total volume 80.
    def _ready(self) -> strategies.MarketView:
        return _market(("42.00", "40"), ("42.20", "40"))

    def test_buys_below_fair_value(self):
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        self.assertIsNotNone(gate.decide("ACME", self._ready(), None, random.Random(1)))

    def test_will_not_buy_above_fair_value(self):
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.20")))
        self.assertIsNone(gate.decide("ACME", self._ready(), None, random.Random(1)))
        self.assertEqual(patience.NO_EDGE, gate.last_reason)

    def test_sells_above_fair_value(self):
        gate = patience.PatienceGate(_Fixed(_intent("SELL", "42.20")))
        self.assertIsNotNone(gate.decide("ACME", self._ready(), None, random.Random(1)))

    def test_will_not_sell_below_fair_value(self):
        gate = patience.PatienceGate(_Fixed(_intent("SELL", "42.00")))
        self.assertIsNone(gate.decide("ACME", self._ready(), None, random.Random(1)))
        self.assertEqual(patience.NO_EDGE, gate.last_reason)

    def test_trading_at_fair_value_is_not_an_advantage(self):
        # Exactly at the mean: no edge either way, so it waits.
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.10")))
        self.assertIsNone(gate.decide("ACME", self._ready(), None, random.Random(1)))
        self.assertEqual(patience.NO_EDGE, gate.last_reason)

    def test_margin_is_measured_in_ticks(self):
        # ACME's tick is 0.01. A 0.05 edge clears 1 tick and 5 ticks, but not 6.
        market = self._ready()
        for ticks, expected in ((1, True), (5, True), (6, False)):
            gate = patience.PatienceGate(
                _Fixed(_intent("BUY", "42.05")), min_edge_ticks=ticks
            )
            traded = gate.decide("ACME", market, None, random.Random(1)) is not None
            self.assertEqual(expected, traded, f"min_edge_ticks={ticks}")

    def test_zero_ticks_disables_the_edge_gate(self):
        # Documented escape hatch: readiness only, i.e. the pre-patience behaviour.
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.20")), min_edge_ticks=0)
        self.assertIsNotNone(gate.decide("ACME", self._ready(), None, random.Random(1)))
        self.assertIsNone(gate.last_reason)

    def test_the_intent_is_passed_through_untouched(self):
        intent = _intent("BUY", "42.00", "7")
        gate = patience.PatienceGate(_Fixed(intent))
        self.assertIs(intent, gate.decide("ACME", self._ready(), None, random.Random(1)))


class DrawInvarianceTest(unittest.TestCase):
    def test_abstaining_consumes_the_same_draws_as_trading(self):
        # If patience shortened the RNG sequence, a seeded run's later decisions would diverge
        # depending on whether earlier ones happened to find an edge.
        favourable = patience.PatienceGate(_Fixed(_intent("BUY", "42.00"), draws=3))
        unfavourable = patience.PatienceGate(_Fixed(_intent("BUY", "42.20"), draws=3))
        market = _market(("42.00", "40"), ("42.20", "40"))

        rng_a = random.Random(7)
        favourable.decide("ACME", market, None, rng_a)
        rng_b = random.Random(7)
        unfavourable.decide("ACME", market, None, rng_b)

        self.assertEqual(rng_a.random(), rng_b.random())

    def test_real_bookfish_is_reproducible_through_the_gate(self):
        market = _market(("42.00", "40"), ("42.10", "60"), ("42.20", "40"))

        def run():
            gate = patience.PatienceGate(strategies.BookfishStrategy())
            rng = random.Random(3)
            return [gate.decide("ACME", market, None, rng) for _ in range(30)]

        self.assertEqual(run(), run())


class LiveBookfishTest(unittest.TestCase):
    """The real sampler through the real gate — does it still trade, and does it wait?"""

    def _market(self) -> strategies.MarketView:
        """A market shaped like the demo's: ``rando`` alone spreads fills over ±1% of the last sale,
        so the traded-volume histogram spans dollars, not ticks (here σ ≈ 0.24 on a 42.10 market)."""
        market = strategies.MarketView()
        for step in range(21):
            price = Decimal("41.60") + Decimal("0.05") * step
            weight = Decimal(100 - abs(step - 10) * 8)  # peaked at 42.10, tails at both ends
            market.record_trade("ACME", price, weight)
        market.set_last_sale("ACME", Decimal("42.10"))
        return market

    def test_trades_sometimes_and_waits_sometimes(self):
        gate = patience.PatienceGate(strategies.BookfishStrategy())
        market = self._market()
        traded = sum(
            1 for seed in range(300) if gate.decide("ACME", market, None, random.Random(seed))
        )
        # A symmetric distribution and a coin-flipped side: roughly a third to a half. Wide bounds,
        # because the point is that neither extreme (never trades / always trades) is what happens.
        self.assertGreater(traded, 60, "patience must not starve the run")
        self.assertLess(traded, 240, "patience must actually decline sometimes")

    def test_waits_indefinitely_when_the_only_reachable_price_is_fair_value(self):
        # The degenerate case, and the one worth stating outright: all the volume is at the last sale,
        # 0.5σ is tighter than the gap to its neighbours, so the only price bookfish can draw is fair
        # value itself. There is no advantage available, so it never trades — by design, not by defect.
        market = strategies.MarketView()
        for price, quantity in (("42.00", "100"), ("42.05", "150"), ("42.10", "300"),
                                ("42.15", "150"), ("42.20", "100")):
            market.record_trade("ACME", Decimal(price), Decimal(quantity))
        market.set_last_sale("ACME", Decimal("42.10"))
        gate = patience.PatienceGate(strategies.BookfishStrategy())
        for seed in range(50):
            self.assertIsNone(gate.decide("ACME", market, None, random.Random(seed)))
            self.assertEqual(patience.NO_EDGE, gate.last_reason)

    def test_acts_once_the_market_moves_away_from_fair_value(self):
        # Same histogram, but the last sale has drifted below where the volume traded: now every
        # in-band price is below fair value, so every BUY draw has an edge. This is the behaviour that
        # makes patience productive rather than merely quiet.
        market = strategies.MarketView()
        for price, quantity in (("42.00", "100"), ("42.05", "150"), ("42.10", "300"),
                                ("42.15", "150"), ("42.20", "100")):
            market.record_trade("ACME", Decimal(price), Decimal(quantity))
        market.set_last_sale("ACME", Decimal("42.00"))
        gate = patience.PatienceGate(strategies.BookfishStrategy())
        intents = [gate.decide("ACME", market, None, random.Random(s)) for s in range(200)]
        traded = [i for i in intents if i is not None]
        self.assertGreater(len(traded), 50)
        self.assertEqual({"BUY"}, {i.side for i in traded}, "only the cheap side has an edge here")

    def test_every_submitted_order_has_an_edge(self):
        gate = patience.PatienceGate(strategies.BookfishStrategy())
        market = self._market()
        stats = strategies.histogram_stats(market.traded_volume["ACME"])
        fair = Decimal(str(stats.mean))
        tick = Decimal("0.01")
        for seed in range(300):
            intent = gate.decide("ACME", market, None, random.Random(seed))
            if intent is None:
                continue
            if intent.side == "BUY":
                self.assertLessEqual(intent.price, fair - tick, f"seed={seed}")
            else:
                self.assertGreaterEqual(intent.price, fair + tick, f"seed={seed}")

    def test_a_cold_market_waits_instead_of_falling_back_to_rando(self):
        # The behaviour this whole gate exists for: no volume seen yet, so no orders — where the bare
        # sampler would have drawn uniformly around the last sale and looked like rando.
        cold = strategies.MarketView()
        cold.set_last_sale("ACME", Decimal("42.10"))
        gate = patience.PatienceGate(strategies.BookfishStrategy())
        bare = strategies.BookfishStrategy()
        for seed in range(50):
            self.assertIsNone(gate.decide("ACME", cold, None, random.Random(seed)))
            self.assertIsNotNone(bare.decide("ACME", cold, None, random.Random(seed)))


class WiringTest(unittest.TestCase):
    def test_by_name_wraps_bookfish_in_patience_inside_liquidity(self):
        strategy = strategies.by_name("bookfish")
        self.assertIsInstance(strategy, liquidity.LiquidityPolicy)
        self.assertIsInstance(strategy.delegate, patience.PatienceGate)
        self.assertIsInstance(strategy.delegate.delegate, strategies.BookfishStrategy)
        self.assertEqual("bookfish", strategy.name)

    def test_booker_and_rando_are_not_patient(self):
        booker = strategies.by_name("booker")
        self.assertIsInstance(booker, liquidity.LiquidityPolicy)
        self.assertIsInstance(booker.delegate, strategies.BookerStrategy)
        self.assertIsInstance(strategies.by_name("rando"), strategies.RandoStrategy)

    def test_bare_bookfish_is_ungated(self):
        self.assertIsInstance(strategies.bare("bookfish"), strategies.BookfishStrategy)

    def test_liquidity_still_forces_a_sell_after_an_abstention(self):
        # Patience *delays* a forced liquidity sell; it must not prevent one. Three ticks: establish
        # the cash baseline, abstain while the account is already under water, then act.
        gate = patience.PatienceGate(_Fixed(_intent("BUY", "42.00")))
        policy = liquidity.LiquidityPolicy(gate)
        market = _market(("42.00", "40"), ("42.20", "40"))
        healthy = liquidity.Portfolio(
            cash_by_currency={"USD": Decimal("1000")}, shares={"ACME": Decimal("500")}
        )
        drained = liquidity.Portfolio(
            cash_by_currency={"USD": Decimal("100")}, shares={"ACME": Decimal("500")}
        )

        self.assertIsNotNone(policy.decide("ACME", market, healthy, random.Random(1)))

        # Tick two: patience has no opinion, so nothing is sent even though cash is below the floor.
        gate.delegate.intent = None
        self.assertIsNone(policy.decide("ACME", market, drained, random.Random(1)))

        # Tick three: patience finds an edge, and the policy overrides its side to raise cash.
        gate.delegate.intent = _intent("BUY", "42.00")
        forced = policy.decide("ACME", market, drained, random.Random(1))
        self.assertIsNotNone(forced)
        self.assertEqual("SELL", forced.side)


if __name__ == "__main__":
    unittest.main()
