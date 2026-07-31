"""Per-investor broker accounts and the slot that identifies them (stories/004).

The property worth protecting is that **accounts do not churn**: locust kills and spawns users on
every ramp and every re-mix, and an identity derived from a spawn counter would open a funded account
per spawn. These tests pin slot reuse, the caching that avoids a needless round trip, and the
fall-back behaviour — because a harness that refuses to trade when it cannot get a private account is
worse than one that shares.
"""

from __future__ import annotations

import io
import json
import unittest
import urllib.error

from fxc_loadgen import accounts


class _FakeOpener:
    """Stands in for urllib.request.urlopen: records URLs, returns scripted bodies."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.urls: list[str] = []

    def __call__(self, request, timeout=None):
        self.urls.append(request.full_url)
        self.timeout = timeout
        response = self.responses.pop(0) if self.responses else {"account": "000100001"}
        if isinstance(response, Exception):
            raise response
        body = json.dumps(response).encode()

        class _Response(io.BytesIO):
            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *exc):
                return False

        return _Response(body)


class SlotPoolTest(unittest.TestCase):
    def test_hands_out_slots_from_zero(self):
        pool = accounts.SlotPool()
        self.assertEqual([0, 1, 2], [pool.claim() for _ in range(3)])

    def test_a_released_slot_is_reused_before_a_new_one(self):
        # The whole point: a ramp down and back up must not invent new identities.
        pool = accounts.SlotPool()
        first, second, third = pool.claim(), pool.claim(), pool.claim()
        pool.release(second)
        self.assertEqual(second, pool.claim())
        self.assertEqual(3, pool.claim(), "only then does it grow")
        self.assertEqual(4, pool.in_use())
        del first, third

    def test_releasing_twice_is_harmless(self):
        pool = accounts.SlotPool()
        slot = pool.claim()
        pool.release(slot)
        pool.release(slot)
        self.assertEqual(0, pool.in_use())

    def test_a_ramp_cycle_does_not_leak_slots(self):
        pool = accounts.SlotPool()
        held = [pool.claim() for _ in range(8)]
        for slot in held[4:]:
            pool.release(slot)
        held = held[:4] + [pool.claim() for _ in range(4)]
        self.assertEqual(8, pool.in_use())
        self.assertEqual(set(range(8)), set(held), "the same eight identities, not sixteen")


class OpenAccountTest(unittest.TestCase):
    def test_posts_the_client_id_as_a_query_parameter(self):
        opener = _FakeOpener({"account": "000100042", "opened": True})
        account = accounts.open_account("http://broker:8083", "locust-3", "Investor locust-3",
                                        opener=opener)
        self.assertEqual("000100042", account)
        self.assertIn("/api/accounts?", opener.urls[0])
        self.assertIn("clientId=locust-3", opener.urls[0])
        self.assertIn("ownerName=Investor+locust-3", opener.urls[0])

    def test_trailing_slash_does_not_double_up(self):
        opener = _FakeOpener({"account": "1"})
        accounts.open_account("http://broker:8083/", "locust-0", opener=opener)
        self.assertTrue(opener.urls[0].startswith("http://broker:8083/api/accounts?"))

    def test_a_refusal_is_reported_with_the_brokers_reason(self):
        refusal = urllib.error.HTTPError(
            "http://broker:8083/api/accounts", 409, "Conflict", {},
            io.BytesIO(b'{"error":"account opening is disabled"}'))
        with self.assertRaises(accounts.AccountError) as caught:
            accounts.open_account("http://broker:8083", "locust-0", opener=_FakeOpener(refusal))
        self.assertIn("409", str(caught.exception))
        self.assertIn("disabled", str(caught.exception))

    def test_an_unreachable_console_is_an_account_error_not_a_crash(self):
        with self.assertRaises(accounts.AccountError):
            accounts.open_account("http://broker:8083", "locust-0",
                                  opener=_FakeOpener(urllib.error.URLError("connection refused")))

    def test_a_reply_without_an_account_is_refused(self):
        with self.assertRaises(accounts.AccountError):
            accounts.open_account("http://broker:8083", "locust-0",
                                  opener=_FakeOpener({"opened": True}))

    def test_a_blank_account_is_refused(self):
        with self.assertRaises(accounts.AccountError):
            accounts.open_account("http://broker:8083", "locust-0", opener=_FakeOpener({"account": ""}))


class AccountRegistryTest(unittest.TestCase):
    def test_claims_a_slot_and_opens_its_account(self):
        opener = _FakeOpener({"account": "000100001"})
        registry = accounts.AccountRegistry("http://broker:8083", opener=opener)
        slot, account = registry.claim()
        self.assertEqual(0, slot)
        self.assertEqual("000100001", account)
        self.assertEqual({"locust-0": "000100001"}, registry.known_accounts())

    def test_client_ids_carry_the_prefix(self):
        registry = accounts.AccountRegistry("http://broker:8083", prefix="swarm")
        self.assertEqual("swarm-4", registry.client_id(4))

    def test_a_respawn_into_the_same_slot_reuses_the_account_without_a_round_trip(self):
        opener = _FakeOpener({"account": "000100001"}, {"account": "000100099"})
        registry = accounts.AccountRegistry("http://broker:8083", opener=opener)
        slot, first = registry.claim()
        registry.release(slot)
        slot_again, second = registry.claim()

        self.assertEqual(slot, slot_again)
        self.assertEqual(first, second, "same slot, same account")
        self.assertEqual(1, len(opener.urls), "and no second POST to learn it")

    def test_concurrent_investors_get_different_accounts(self):
        opener = _FakeOpener({"account": "000100001"}, {"account": "000100002"})
        registry = accounts.AccountRegistry("http://broker:8083", opener=opener)
        _, first = registry.claim()
        _, second = registry.claim()
        self.assertNotEqual(first, second)
        self.assertEqual(2, registry.slots_in_use())

    def test_a_failed_open_releases_the_slot(self):
        # Otherwise one unreachable moment would shift every later investor's identity by one, and the
        # broker would accumulate an account for each shift.
        opener = _FakeOpener(urllib.error.URLError("nope"), {"account": "000100001"})
        registry = accounts.AccountRegistry("http://broker:8083", opener=opener)
        with self.assertRaises(accounts.AccountError):
            registry.claim()
        self.assertEqual(0, registry.slots_in_use())
        slot, account = registry.claim()
        self.assertEqual(0, slot, "the freed slot is reused rather than skipped")
        self.assertEqual("000100001", account)

    def test_a_ramp_up_and_down_settles_at_the_high_water_mark(self):
        opener = _FakeOpener(*[{"account": f"00010000{i}"} for i in range(1, 9)])
        registry = accounts.AccountRegistry("http://broker:8083", opener=opener)
        held = [registry.claim()[0] for _ in range(4)]        # ramp to 4
        for slot in held[2:]:
            registry.release(slot)                            # ramp down to 2
        held = held[:2] + [registry.claim()[0] for _ in range(2)]   # back to 4

        self.assertEqual(4, registry.slots_in_use())
        self.assertEqual(4, len(registry.known_accounts()), "four accounts, not six")


if __name__ == "__main__":
    unittest.main()
