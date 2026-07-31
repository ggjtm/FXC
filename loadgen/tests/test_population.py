"""The live investor population — the part of the harness that needs locust installed.

Skipped on a bare host so ``python3 -m unittest discover`` stays pip-free (the promise
``loadgen/README.md`` makes); runs in the container image and in the ``scripts/loadtest.sh``
virtualenv, where it pins the wiring between ``fxc_loadgen.mix`` and the locust user:

* a spawned investor is registered, assigned a strategy, and unregistered when it stops;
* changing a share moves live investors between strategies without spawning or killing any;
* the stats row type follows the strategy an investor currently runs.

The apportionment itself is tested pip-free in ``test_mix.py``; this is about the application of it.
"""

from __future__ import annotations

import importlib.util
import unittest
from argparse import Namespace
from collections import Counter

LOCUST_AVAILABLE = importlib.util.find_spec("locust") is not None


def _options(**overrides) -> Namespace:
    defaults = dict(
        ofx_user="investor",
        ofx_password="secret",
        accounts="000000001,000000002",
        broker_console_url="",
        client_prefix="locust",
        symbols="ACME",
        strategy="",
        mix_rando=0,
        mix_booker=0,
        mix_bookfish=0,
        seed=1,
        seed_price="42.10",
        portfolio_refresh_ms=5000,
        bookfish_min_volume=50,
        bookfish_min_edge_ticks=1,
        mix_refresh_ms=1000,
    )
    defaults.update(overrides)
    return Namespace(**defaults)


@unittest.skipUnless(LOCUST_AVAILABLE, "locust is not installed")
class PopulationTest(unittest.TestCase):
    def setUp(self):
        import locustfile
        from locust.env import Environment

        self.locustfile = locustfile
        self.options = _options()
        # A real Environment: an HttpUser needs its events and its host, and a stub of those would be
        # testing the stub.
        self.environment = Environment(
            user_classes=[locustfile.InvestorUser], host="http://localhost:8082"
        )
        self.environment.parsed_options = self.options
        # Locust's runner stamps the host onto the class before spawning; do the same by hand.
        locustfile.InvestorUser.host = self.environment.host
        locustfile.POPULATION.clear()

    def tearDown(self):
        self.locustfile.POPULATION.clear()
        self.locustfile.InvestorUser.host = None
        self.locustfile._registry = None

    def _spawn(self, count: int) -> list:
        users = []
        for _ in range(count):
            user = self.locustfile.InvestorUser(self.environment)
            user.on_start()
            users.append(user)
        return users

    def _counts(self) -> dict[str, int]:
        return dict(Counter(user.strategy_name for user in self.locustfile.POPULATION))

    def test_a_spawned_investor_is_registered_and_can_trade_immediately(self):
        # A user with no strategy would idle; on_start must not leave it waiting for the reconciler.
        (user,) = self._spawn(1)
        self.assertEqual([user], self.locustfile.POPULATION)
        self.assertIsNotNone(user.strategy)
        self.assertIn(user.strategy_name, ("rando", "booker", "bookfish"))

    def test_stopping_unregisters(self):
        users = self._spawn(3)
        users[1].on_stop()
        self.assertEqual([users[0], users[2]], self.locustfile.POPULATION)

    def test_default_is_one_of_each(self):
        self._spawn(8)
        self.assertEqual({"rando": 3, "booker": 3, "bookfish": 2}, self._counts())

    def test_shares_apportion_the_population(self):
        self.options.mix_rando = 1
        self.options.mix_booker = 4
        self.options.mix_bookfish = 2
        self._spawn(16)
        self.assertEqual({"rando": 2, "booker": 9, "bookfish": 5}, self._counts())

    def test_a_share_change_moves_live_investors_without_respawning(self):
        users = self._spawn(8)
        self.assertEqual({"rando": 3, "booker": 3, "bookfish": 2}, self._counts())

        # Shares 1/3/1 over 8 investors apportion to 2/5/1.
        self.options.mix_rando = 1
        self.options.mix_booker = 3
        self.options.mix_bookfish = 1
        self.locustfile._reconcile_mix(self.environment)

        self.assertEqual({"rando": 2, "booker": 5, "bookfish": 1}, self._counts())
        self.assertEqual(users, self.locustfile.POPULATION, "no investor was replaced")

    def test_the_oldest_investors_keep_their_strategy_across_a_re_mix(self):
        # A partial re-mix (all three still present, bookfish-heavy) must move the newest investors and
        # leave the oldest alone — their liquidity baseline and RNG history are worth keeping.
        users = self._spawn(8)
        first_three = [user.strategy_name for user in users[:3]]
        self.assertEqual(3, len(set(first_three)), "the fixture needs one of each up front")
        self.options.mix_rando = 1
        self.options.mix_booker = 1
        self.options.mix_bookfish = 5
        self.locustfile._reconcile_mix(self.environment)
        self.assertEqual({"rando": 1, "booker": 1, "bookfish": 6}, self._counts())
        self.assertEqual(first_three, [user.strategy_name for user in users[:3]])

    def test_a_zero_share_empties_that_strategy(self):
        self._spawn(6)
        self.options.mix_booker = 1  # booker only
        self.locustfile._reconcile_mix(self.environment)
        self.assertEqual({"booker": 6}, self._counts())

    def test_strategy_shorthand_still_selects_a_single_type(self):
        self.options.strategy = "bookfish"
        self._spawn(4)
        self.assertEqual({"bookfish": 4}, self._counts())

    def test_reconciling_is_idempotent(self):
        self._spawn(8)
        before = [user.strategy_name for user in self.locustfile.POPULATION]
        self.locustfile._reconcile_mix(self.environment)
        self.assertEqual(before, [user.strategy_name for user in self.locustfile.POPULATION])

    def test_an_unusable_mix_leaves_the_population_alone(self):
        self._spawn(4)
        before = [user.strategy_name for user in self.locustfile.POPULATION]
        self.options.mix_rando = -3
        self.locustfile._reconcile_mix(self.environment)  # must not raise
        self.assertEqual(before, [user.strategy_name for user in self.locustfile.POPULATION])

    def test_an_empty_population_is_not_an_error(self):
        self.locustfile._reconcile_mix(self.environment)  # must not raise

    def test_stats_type_follows_the_current_strategy(self):
        self.options.strategy = "rando"
        (user,) = self._spawn(1)
        self.assertEqual("RANDO", user.stats_type)
        user.adopt("bookfish", self.options)
        self.assertEqual("BOOKFISH", user.stats_type)

    def test_only_bookfish_carries_a_patience_gate(self):
        self.options.strategy = "bookfish"
        (user,) = self._spawn(1)
        self.assertIsNotNone(user.patience)
        self.assertEqual(50, int(user.patience.min_volume))
        self.assertEqual(1, user.patience.min_edge_ticks)
        user.adopt("booker", self.options)
        self.assertIsNone(user.patience)

    def test_patience_thresholds_come_from_the_options(self):
        self.options.strategy = "bookfish"
        self.options.bookfish_min_volume = 250
        self.options.bookfish_min_edge_ticks = 4
        (user,) = self._spawn(1)
        self.assertEqual(250, int(user.patience.min_volume))
        self.assertEqual(4, user.patience.min_edge_ticks)

    def test_editing_the_patience_thresholds_takes_effect_live(self):
        self.options.strategy = "bookfish"
        (user,) = self._spawn(1)
        self.options.bookfish_min_volume = 500
        self.options.bookfish_min_edge_ticks = 7
        self.locustfile._reconcile_mix(self.environment)
        self.assertEqual(500, int(user.patience.min_volume))
        self.assertEqual(7, user.patience.min_edge_ticks)

    def test_adopting_the_same_strategy_keeps_the_instance(self):
        # Re-adopting on every reconciliation would reset the liquidity policy's cash baseline once a
        # second, quietly disabling the floor it exists to defend.
        self.options.strategy = "booker"
        (user,) = self._spawn(1)
        strategy = user.strategy
        user.adopt("booker", self.options)
        self.assertIs(strategy, user.strategy)

    def test_a_strategy_change_keeps_the_investor_s_identity(self):
        (user,) = self._spawn(1)
        account, rng, portfolio = user.account, user.rng, user.portfolio
        user.adopt("bookfish", self.options)
        self.assertEqual(account, user.account)
        self.assertIs(rng, user.rng)
        self.assertIs(portfolio, user.portfolio)

    def test_all_investors_share_one_market_view(self):
        # The shared view is what makes bookfish's histogram worth sampling.
        users = self._spawn(3)
        self.assertIs(self.locustfile.MARKET, users[0].market)
        self.assertIs(users[0].market, users[2].market)

    def test_accounts_are_spread_round_robin_when_opening_is_unavailable(self):
        # Which account the first investor lands on depends on the process-wide spawn counter, so the
        # property to assert is the alternation, not the starting point.
        users = self._spawn(4)
        assigned = [user.account for user in users]
        self.assertEqual({"000000001", "000000002"}, set(assigned))
        for earlier, later in zip(assigned, assigned[1:]):
            self.assertNotEqual(earlier, later, f"round robin should alternate: {assigned}")

    # --- per-investor accounts (stories/004) ---

    def _with_registry(self, *responses):
        from fxc_loadgen import accounts
        from tests.test_accounts import _FakeOpener

        self.locustfile._registry = accounts.AccountRegistry(
            "http://broker:8083", opener=_FakeOpener(*responses))
        return self.locustfile._registry

    def test_each_investor_gets_its_own_account(self):
        self._with_registry({"account": "000100001"}, {"account": "000100002"},
                            {"account": "000100003"})
        users = self._spawn(3)
        self.assertEqual(["000100001", "000100002", "000100003"],
                         [user.account for user in users])
        self.assertEqual([0, 1, 2], [user.slot for user in users])

    def test_a_stopped_investor_frees_its_slot_for_the_next_one(self):
        registry = self._with_registry({"account": "000100001"}, {"account": "000100002"})
        users = self._spawn(2)
        users[0].on_stop()
        self.assertEqual(1, registry.slots_in_use())

        # The replacement takes the freed slot, and with it the same account — this is what keeps a
        # ramp cycle from opening an account per spawn.
        (replacement,) = self._spawn(1)
        self.assertEqual(0, replacement.slot)
        self.assertEqual("000100001", replacement.account)
        self.assertEqual(2, len(registry.known_accounts()), "two accounts for three spawns")

    def test_a_broker_that_will_not_open_falls_back_to_the_shared_accounts(self):
        import urllib.error

        self._with_registry(urllib.error.URLError("refused"), urllib.error.URLError("refused"))
        users = self._spawn(2)
        self.assertEqual(["000000001", "000000002"], [user.account for user in users])
        self.assertEqual([None, None], [user.slot for user in users], "no slot was kept")


if __name__ == "__main__":
    unittest.main()
