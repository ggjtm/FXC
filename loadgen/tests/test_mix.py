"""The investor mix: share resolution and apportionment (FxcInvestor/docs/stories/007).

These are the numbers the Locust UI's three ``mix-*`` fields turn into: how many live investors run each
strategy, and which of them move when a share changes. An error here is an error in what the demo is
actually running. Locust's own dispatcher is deliberately not relied on for the split — it cannot express
a live mix (docs/PROBLEMS.md P16) — so this is the authority, and it is pure and pip-free on purpose.
"""

from __future__ import annotations

import unittest
from collections import Counter

from fxc_loadgen import mix


class ResolveSharesTest(unittest.TestCase):
    def test_default_is_one_of_each(self):
        self.assertEqual({"rando": 1, "booker": 1, "bookfish": 1}, mix.resolve_shares(0, 0, 0))

    def test_explicit_shares_win(self):
        self.assertEqual({"rando": 1, "booker": 4, "bookfish": 2}, mix.resolve_shares(1, 4, 2))

    def test_zero_share_excludes_a_strategy(self):
        self.assertEqual({"booker": 3, "bookfish": 1}, mix.resolve_shares(0, 3, 1))

    def test_strategy_shorthand_means_all_of_one_type(self):
        # Backwards compatibility: scripts/loadtest.sh --strategy bookfish predates the mix.
        self.assertEqual({"bookfish": 1}, mix.resolve_shares(0, 0, 0, "bookfish"))
        self.assertEqual({"booker": 1}, mix.resolve_shares(0, 0, 0, "BOOKER"))

    def test_explicit_shares_beat_the_strategy_shorthand(self):
        # docker-compose can carry both; the more specific option has to win.
        self.assertEqual({"rando": 2}, mix.resolve_shares(2, 0, 0, "booker"))

    def test_result_is_in_canonical_order(self):
        self.assertEqual(["rando", "booker", "bookfish"], list(mix.resolve_shares(5, 5, 5)))

    def test_negative_shares_are_refused(self):
        with self.assertRaises(ValueError) as caught:
            mix.resolve_shares(-1, 0, 0)
        self.assertIn("rando", str(caught.exception))

    def test_unknown_strategy_lists_the_alternatives(self):
        with self.assertRaises(ValueError) as caught:
            mix.resolve_shares(0, 0, 0, "nope")
        message = str(caught.exception)
        for name in ("rando", "booker", "bookfish"):
            self.assertIn(name, message)


class ApportionTest(unittest.TestCase):
    def test_exact_when_the_total_equals_the_share_sum(self):
        counts, dropped = mix.apportion(16, {"rando": 4, "booker": 10, "bookfish": 2})
        self.assertEqual({"rando": 4, "booker": 10, "bookfish": 2}, counts)
        self.assertEqual([], dropped)

    def test_sums_to_the_total(self):
        for total in range(3, 40):
            counts, dropped = mix.apportion(total, {"rando": 1, "booker": 3, "bookfish": 2})
            self.assertEqual(total, sum(counts.values()), f"total={total} counts={counts}")
            self.assertEqual([], dropped)

    def test_every_requested_type_gets_at_least_one(self):
        # The point of the repair pass: a lopsided mix must not silently delete a strategy.
        counts, dropped = mix.apportion(3, {"rando": 1, "booker": 100, "bookfish": 1})
        self.assertEqual({"rando": 1, "booker": 1, "bookfish": 1}, counts)
        self.assertEqual([], dropped)

    def test_a_tiny_share_still_appears_in_a_large_run(self):
        counts, _ = mix.apportion(50, {"rando": 1, "booker": 999})
        self.assertEqual(1, counts["rando"])
        self.assertEqual(49, counts["booker"])

    def test_repair_borrows_from_the_largest_holder(self):
        counts, _ = mix.apportion(10, {"rando": 1, "booker": 50, "bookfish": 200})
        self.assertEqual(10, sum(counts.values()))
        self.assertEqual({"rando", "booker", "bookfish"}, set(counts))
        self.assertEqual(1, counts["rando"])

    def test_verification_example_from_the_story(self):
        # The mid-run steering example: 16 users at shares 1/4/2.
        counts, _ = mix.apportion(16, {"rando": 1, "booker": 4, "bookfish": 2})
        self.assertEqual({"rando": 2, "booker": 9, "bookfish": 5}, counts)

    def test_default_mix_at_the_container_default_user_count(self):
        # docker/locust/Dockerfile ships LOCUST_USERS=8; this is what the demo actually runs.
        counts, dropped = mix.apportion(8, {"rando": 1, "booker": 1, "bookfish": 1})
        self.assertEqual({"rando": 3, "booker": 3, "bookfish": 2}, counts)
        self.assertEqual([], dropped)

    def test_remainder_goes_to_the_largest_share_first(self):
        counts, _ = mix.apportion(10, {"rando": 1, "booker": 5})
        self.assertEqual({"rando": 2, "booker": 8}, counts)

    def test_equal_remainders_break_ties_in_canonical_order(self):
        counts, _ = mix.apportion(4, {"rando": 1, "booker": 1, "bookfish": 1})
        self.assertEqual({"rando": 2, "booker": 1, "bookfish": 1}, counts)

    def test_too_few_users_reports_what_did_not_fit(self):
        counts, dropped = mix.apportion(2, {"rando": 1, "booker": 5, "bookfish": 1})
        # Highest share is kept first, then canonical order.
        self.assertEqual({"rando": 1, "booker": 1}, counts)
        self.assertEqual(["bookfish"], dropped)

    def test_zero_users_drops_everything_and_says_so(self):
        counts, dropped = mix.apportion(0, {"rando": 1, "booker": 1})
        self.assertEqual({}, counts)
        self.assertEqual(["rando", "booker"], dropped)

    def test_no_shares_is_empty_rather_than_an_error(self):
        self.assertEqual(({}, []), mix.apportion(8, {}))

    def test_single_strategy_takes_every_user(self):
        counts, dropped = mix.apportion(12, {"bookfish": 1})
        self.assertEqual({"bookfish": 12}, counts)
        self.assertEqual([], dropped)

    def test_counts_are_in_canonical_order(self):
        counts, _ = mix.apportion(9, {"bookfish": 1, "rando": 1, "booker": 1})
        self.assertEqual(["rando", "booker", "bookfish"], list(counts))

    def test_deterministic(self):
        shares = {"rando": 2, "booker": 7, "bookfish": 3}
        self.assertEqual(mix.apportion(13, shares), mix.apportion(13, shares))


class ReassignTest(unittest.TestCase):
    def test_places_new_investors_without_touching_anyone(self):
        current = ["rando", "booker", None, None]
        self.assertEqual(
            ["rando", "booker", "booker", "bookfish"],
            mix.reassign(current, {"rando": 1, "booker": 2, "bookfish": 1}),
        )

    def test_is_idempotent(self):
        targets = {"rando": 2, "booker": 1}
        once = mix.reassign([None, None, None], targets)
        self.assertEqual(once, mix.reassign(once, targets))

    def test_nothing_moves_when_already_on_target(self):
        current = ["rando", "booker", "bookfish"]
        self.assertEqual(
            current, mix.reassign(current, {"rando": 1, "booker": 1, "bookfish": 1})
        )

    def test_takes_from_the_over_target_strategy_newest_first(self):
        # The oldest investors keep their strategy: their liquidity baseline and RNG history are worth
        # more than symmetry.
        current = ["rando", "rando", "rando", "rando"]
        self.assertEqual(
            ["rando", "rando", "bookfish", "booker"],
            mix.reassign(current, {"rando": 2, "booker": 1, "bookfish": 1}),
        )

    def test_moves_the_minimum_number_of_investors(self):
        current = ["rando", "rando", "booker", "booker", "bookfish", "bookfish"]
        result = mix.reassign(current, {"rando": 3, "booker": 2, "bookfish": 1})
        self.assertEqual(1, sum(1 for a, b in zip(current, result) if a != b))
        self.assertEqual({"rando": 3, "booker": 2, "bookfish": 1}, dict(Counter(result)))

    def test_corrects_a_skew_left_by_a_ramp_down(self):
        # Locust kills whichever users it likes; whoever is left gets reassigned rather than churned.
        current = ["booker", "booker", "booker"]
        result = mix.reassign(current, {"rando": 1, "booker": 1, "bookfish": 1})
        self.assertEqual({"rando": 1, "booker": 1, "bookfish": 1}, dict(Counter(result)))

    def test_a_strategy_dropped_from_the_mix_is_reassigned_away(self):
        current = ["rando", "rando", "booker"]
        result = mix.reassign(current, {"booker": 3})
        self.assertEqual(["booker", "booker", "booker"], result)

    def test_an_empty_population_stays_empty(self):
        self.assertEqual([], mix.reassign([], {"rando": 1}))

    def test_more_investors_than_targets_leaves_the_surplus_alone(self):
        # Targets come from apportion(), which always sums to the population, so this is defensive:
        # it must not crash or blank anyone out.
        current = ["rando", "booker", "bookfish"]
        self.assertEqual(current, mix.reassign(current, {"rando": 1}))

    def test_end_to_end_from_shares(self):
        counts, _ = mix.apportion(8, mix.resolve_shares(1, 4, 2))
        assignment = mix.reassign([None] * 8, counts)
        self.assertEqual(counts, dict(Counter(assignment)))


class FormatMixTest(unittest.TestCase):
    def test_reads_in_canonical_order(self):
        self.assertEqual(
            "rando=2 booker=8 bookfish=4",
            mix.format_mix({"bookfish": 4, "rando": 2, "booker": 8}),
        )

    def test_omits_absent_strategies(self):
        self.assertEqual("booker=8", mix.format_mix({"booker": 8}))


if __name__ == "__main__":
    unittest.main()
