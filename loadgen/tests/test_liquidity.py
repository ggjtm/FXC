"""The liquidity policy. Twin of Java's ``LiquidityAwareStrategyTest`` — same cases, same numbers.

The point of the policy is that a run can continue indefinitely instead of exhausting one side of the
account and degrading into a reject stream. So the tests are mostly about the boundaries: what happens
as cash runs out, as holdings run out, and when there is no data at all.
"""

from __future__ import annotations

import random
import unittest
from decimal import Decimal

from fxc_loadgen import liquidity, strategies


class _FixedStrategy:
    """A stand-in sampler that always wants the same thing, so the policy is what is under test."""

    def __init__(self, side: str, price: str, quantity: str, name: str = "booker"):
        self.side, self.price, self.quantity, self.name = side, Decimal(price), Decimal(quantity), name

    def decide(self, symbol, market, portfolio, rng):
        return strategies.OrderIntent(side=self.side, price=self.price, quantity=self.quantity)


class _SilentStrategy:
    name = "booker"

    def decide(self, symbol, market, portfolio, rng):
        return None


def _seeded(cash="1000000", shares="1000") -> liquidity.Portfolio:
    """The demo's seeded account: $1,000,000 and 1,000 ACME."""
    return liquidity.Portfolio(
        cash_by_currency={"USD": Decimal(cash)}, shares={"ACME": Decimal(shares)}
    )


RNG = random.Random(1)
MARKET = strategies.MarketView()


class PassthroughTest(unittest.TestCase):
    """In normal operation the policy must be invisible."""

    def test_normal_buy_is_unchanged(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        intent = policy.decide("ACME", MARKET, _seeded(), RNG)
        self.assertEqual("BUY", intent.side)
        self.assertEqual(Decimal("10"), intent.quantity, "10 shares is nothing against $1M")

    def test_normal_sell_is_unchanged(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("SELL", "42.10", "10"))
        intent = policy.decide("ACME", MARKET, _seeded(), RNG)
        self.assertEqual("SELL", intent.side)
        self.assertEqual(Decimal("10"), intent.quantity)

    def test_a_silent_delegate_stays_silent(self):
        policy = liquidity.LiquidityPolicy(_SilentStrategy())
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(), RNG))

    def test_name_delegates(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "1", name="bookfish"))
        self.assertEqual("bookfish", policy.name)


class FailsClosedTest(unittest.TestCase):
    """Without holdings data the policy must decline, not guess."""

    def test_no_portfolio(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        self.assertIsNone(policy.decide("ACME", MARKET, None, RNG))

    def test_empty_portfolio(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        self.assertIsNone(policy.decide("ACME", MARKET, liquidity.Portfolio(), RNG))

    def test_unknown_symbol(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        self.assertIsNone(policy.decide("NOPE", MARKET, _seeded(), RNG))

    def test_non_positive_price(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "0", "10"))
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(), RNG))


class BuyScalingTest(unittest.TestCase):
    """Buying is scaled to available cash — the first half of the requirement."""

    def test_buy_capped_when_cash_is_nearly_gone(self):
        # Floor = 25% of the baseline. Establish the baseline at $1M, then drop cash to $300k.
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)  # baseline = 1,000,000 -> floor 250,000

        # Spendable = (300,000 - 250,000) * 0.10 = 5,000 -> 5000/42.10 = 118 shares. 10 < 118, so
        # still unconstrained.
        intent = policy.decide("ACME", MARKET, _seeded(cash="300000"), RNG)
        self.assertEqual(Decimal("10"), intent.quantity)

        # Spendable = (250,100 - 250,000) * 0.10 = 10 -> 10/42.10 = 0 whole shares -> decline.
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(cash="250100"), RNG))

    def test_buy_declines_at_the_floor(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)
        # Exactly at the floor: nothing spendable, and there are shares so no forced sell either.
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(cash="250000"), RNG))

    def test_buying_can_never_breach_the_floor(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "100000"))
        policy.decide("ACME", MARKET, _seeded(), RNG)
        intent = policy.decide("ACME", MARKET, _seeded(cash="1000000"), RNG)
        # Spendable = (1,000,000 - 250,000) * 0.10 = 75,000 -> 75,000/42.10 = 1781 shares.
        self.assertEqual(Decimal("1781"), intent.quantity)
        cost = intent.quantity * Decimal("42.10")
        self.assertLess(cost, Decimal("1000000") - Decimal("250000"), "must not cross the floor")

    def test_budget_fraction_is_configurable(self):
        policy = liquidity.LiquidityPolicy(
            _FixedStrategy("BUY", "42.10", "100000"), buy_budget_fraction=Decimal("1.0")
        )
        policy.decide("ACME", MARKET, _seeded(), RNG)
        intent = policy.decide("ACME", MARKET, _seeded(cash="1000000"), RNG)
        # Whole spendable amount now: 750,000/42.10 = 17814.
        self.assertEqual(Decimal("17814"), intent.quantity)


class SellToRestoreLiquidityTest(unittest.TestCase):
    """Selling assets to maintain liquidity — the second half of the requirement."""

    def test_below_the_floor_forces_a_sell_even_when_the_sampler_wanted_to_buy(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)  # baseline 1,000,000 -> floor 250,000

        intent = policy.decide("ACME", MARKET, _seeded(cash="100000"), RNG)
        self.assertIsNotNone(intent)
        self.assertEqual("SELL", intent.side, "must raise cash, whatever the sampler asked for")

    def test_forced_sell_is_sized_to_restore_the_floor(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)
        # Shortfall = 250,000 - 100,000 = 150,000 -> 150,000/42.10 = 3563 shares needed, but only
        # 1,000 are held, so the sell is bounded by holdings.
        intent = policy.decide("ACME", MARKET, _seeded(cash="100000", shares="1000"), RNG)
        self.assertEqual(Decimal("1000"), intent.quantity)

    def test_forced_sell_stops_at_what_is_needed(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)
        # Shortfall = 250,000 - 249,000 = 1,000 -> 1,000/42.10 = 24 shares (ceiling), far below the
        # 5,000 held, so it sells only what it needs.
        intent = policy.decide("ACME", MARKET, _seeded(cash="249000", shares="5000"), RNG)
        self.assertEqual(Decimal("24"), intent.quantity)

    def test_no_holdings_and_no_cash_declines_rather_than_shorting(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "42.10", "10"))
        policy.decide("ACME", MARKET, _seeded(), RNG)
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(cash="0", shares="0"), RNG))

    def test_floor_fraction_is_configurable(self):
        policy = liquidity.LiquidityPolicy(
            _FixedStrategy("BUY", "42.10", "10"), cash_floor_fraction=Decimal("0.90")
        )
        policy.decide("ACME", MARKET, _seeded(), RNG)  # floor = 900,000
        # 500,000 is below a 90% floor, so it must sell.
        intent = policy.decide("ACME", MARKET, _seeded(cash="500000"), RNG)
        self.assertEqual("SELL", intent.side)


class NoShortingTest(unittest.TestCase):
    """The broker rejects a sell of more than is held; clamp instead of being rejected."""

    def test_sell_clamped_to_holdings(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("SELL", "42.10", "10"))
        intent = policy.decide("ACME", MARKET, _seeded(shares="4"), RNG)
        self.assertEqual(Decimal("4"), intent.quantity)

    def test_sell_declines_with_no_holdings(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("SELL", "42.10", "10"))
        self.assertIsNone(policy.decide("ACME", MARKET, _seeded(shares="0"), RNG))


class FxTest(unittest.TestCase):
    """FX works too: an FX sell delivers base currency, so that is the sell bound."""

    def _fx_portfolio(self, usd="1000000", eur="0") -> liquidity.Portfolio:
        return liquidity.Portfolio(
            cash_by_currency={"USD": Decimal(usd), "EUR": Decimal(eur)}, shares={}
        )

    def test_fx_buy_capped_against_the_quote_currency(self):
        # The delegate must want more than the cap, or the cap is not what is being tested.
        policy = liquidity.LiquidityPolicy(_FixedStrategy("BUY", "1.08420", "100000"))
        policy.decide("EUR/USD", MARKET, self._fx_portfolio(), RNG)
        intent = policy.decide("EUR/USD", MARKET, self._fx_portfolio(usd="1000000"), RNG)
        # Spendable = 750,000 * 0.10 = 75,000 -> /1.0842 = 69,175 -> floored to the 1,000 lot.
        self.assertEqual(Decimal("69000"), intent.quantity)
        self.assertEqual(Decimal(0), intent.quantity % Decimal("1000"), "on the FX lot grid")

    def test_fx_sell_bounded_by_the_base_currency_balance(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("SELL", "1.08420", "5000"))
        intent = policy.decide("EUR/USD", MARKET, self._fx_portfolio(eur="2500"), RNG)
        # Only 2,500 EUR held; floored to the 1,000 lot.
        self.assertEqual(Decimal("2000"), intent.quantity)

    def test_fx_sell_declines_with_no_base_currency(self):
        policy = liquidity.LiquidityPolicy(_FixedStrategy("SELL", "1.08420", "1000"))
        self.assertIsNone(policy.decide("EUR/USD", MARKET, self._fx_portfolio(eur="0"), RNG))


class WiringTest(unittest.TestCase):
    """Which strategies are wrapped — mirrors Java's Strategies.byName."""

    def test_rando_is_not_wrapped(self):
        self.assertIsInstance(strategies.by_name("rando"), strategies.RandoStrategy)

    def test_booker_and_bookfish_are_wrapped(self):
        for name in ("booker", "bookfish"):
            strategy = strategies.by_name(name)
            self.assertIsInstance(strategy, liquidity.LiquidityPolicy, name)
            self.assertEqual(name, strategy.name)

    def test_bare_returns_unwrapped_samplers(self):
        for name in ("rando", "booker", "bookfish"):
            self.assertNotIsInstance(strategies.bare(name), liquidity.LiquidityPolicy, name)

    def test_naive_rando_still_ignores_the_portfolio(self):
        # The whole reason for a decorator: rando's behaviour must be provably untouched.
        bare = strategies.bare("rando")
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        with_holdings = bare.decide("ACME", market, _seeded(), random.Random(5))
        without = bare.decide("ACME", market, None, random.Random(5))
        self.assertEqual(with_holdings, without)


class SustainabilityTest(unittest.TestCase):
    """The acceptance property: flow continues instead of degrading into rejections."""

    def test_a_long_run_never_asks_for_something_the_broker_would_reject(self):
        # Simulate holdings drifting as orders fill, and assert every emitted intent is fundable and
        # backed by holdings — i.e. would not be rejected.
        policy = liquidity.LiquidityPolicy(strategies.bare("booker"))
        market = strategies.MarketView()
        market.set_last_sale("ACME", Decimal("42.10"))
        rng = random.Random(4)
        cash, shares = Decimal("1000000"), Decimal("1000")
        emitted = 0

        for _ in range(400):
            portfolio = liquidity.Portfolio(
                cash_by_currency={"USD": cash}, shares={"ACME": shares}
            )
            intent = policy.decide("ACME", market, portfolio, rng)
            if intent is None:
                continue
            emitted += 1
            notional = intent.price * intent.quantity
            if intent.side == "BUY":
                self.assertLessEqual(notional, cash, "a buy must be fundable")
                cash -= notional
                shares += intent.quantity
            else:
                self.assertLessEqual(intent.quantity, shares, "a sell must be covered — no shorting")
                cash += notional
                shares -= intent.quantity
            self.assertGreaterEqual(cash, Decimal(0))
            self.assertGreaterEqual(shares, Decimal(0))

        self.assertGreater(emitted, 200, "the policy should keep trading, not shut down")
        # And it should still be solvent at the end, which the naive version would not be.
        self.assertGreater(cash, Decimal(0))


if __name__ == "__main__":
    unittest.main()
