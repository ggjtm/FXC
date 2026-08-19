"""Reading market-wide traded volume from the exchange chart feed (story 007).

The payload shape is pinned against ``FeedHttpServer.candleJson`` — the exchange's own serialiser —
because this is a second consumer of an endpoint written for a browser, and nothing would fail loudly
if the shape drifted: a mis-parsed body looks like a market with no volume, which is indistinguishable
from a quiet market unless the parse is tested.
"""

from __future__ import annotations

import unittest
from decimal import Decimal

from fxc_loadgen import marketfeed, strategies

#: Verbatim shape of FeedHttpServer.candleJson (FxcExchange/.../feed/FeedHttpServer.java:188).
CANDLE_RESPONSE = {
    "symbol": "ARVX",
    "start": 1_753_800_000_000,
    "end": 1_753_800_900_000,
    "granularityMs": 60_000,
    "candles": [{"t": 1_753_800_000_000, "o": 42.1, "h": 42.3, "l": 42.0, "c": 42.2, "v": 120}],
    "volumeByPrice": [
        {"price": 42.0, "volume": 40},
        {"price": 42.1, "volume": 60},
        {"price": 42.2, "volume": 20},
    ],
}


class VolumeByPriceTest(unittest.TestCase):
    def test_parses_the_exchange_payload(self):
        self.assertEqual(
            {Decimal("42.0"): Decimal("40"), Decimal("42.1"): Decimal("60"),
             Decimal("42.2"): Decimal("20")},
            marketfeed.volume_by_price(CANDLE_RESPONSE),
        )

    def test_prices_land_on_the_same_key_as_ofx_observations(self):
        # The feed says 42.1, an OFX response says 42.10. They must be one bin, not two.
        histogram = marketfeed.volume_by_price({"volumeByPrice": [{"price": 42.1, "volume": 5}]})
        self.assertEqual([Decimal("42.10")], list(histogram))

    def test_missing_volume_by_price_is_empty_not_fatal(self):
        self.assertEqual({}, marketfeed.volume_by_price({"symbol": "ARVX", "candles": []}))

    def test_empty_window_is_empty(self):
        self.assertEqual({}, marketfeed.volume_by_price({"volumeByPrice": []}))

    def test_non_object_bodies_are_empty(self):
        for payload in (None, [], "nope", 42):
            self.assertEqual({}, marketfeed.volume_by_price(payload), payload)

    def test_malformed_levels_are_skipped_individually(self):
        histogram = marketfeed.volume_by_price(
            {
                "volumeByPrice": [
                    {"price": 42.1, "volume": 10},
                    {"price": None, "volume": 10},
                    {"price": 42.2},
                    {"volume": 10},
                    {"price": "abc", "volume": 10},
                    {"price": 42.3, "volume": 0},
                    {"price": -1, "volume": 10},
                    "not a level",
                ]
            }
        )
        self.assertEqual({Decimal("42.1"): Decimal("10")}, histogram)

    def test_repeated_prices_are_summed(self):
        histogram = marketfeed.volume_by_price(
            {"volumeByPrice": [{"price": 42.1, "volume": 10}, {"price": 42.10, "volume": 5}]}
        )
        self.assertEqual({Decimal("42.1"): Decimal("15")}, histogram)


class CandlesUrlTest(unittest.TestCase):
    def test_asks_for_a_bounded_window(self):
        url = marketfeed.candles_url("http://exchange:8090", "ARVX", window_ms=60_000,
                                     now_ms=1_753_800_060_000)
        self.assertEqual(
            "http://exchange:8090/api/candles?symbol=ARVX&granularity=1m&start=1753800000000", url
        )

    def test_trailing_slash_does_not_double_up(self):
        url = marketfeed.candles_url("http://exchange:8090/", "ARVX", now_ms=0)
        self.assertIn("http://exchange:8090/api/candles?", url)

    def test_fx_symbols_are_escaped(self):
        # EUR/USD's slash would otherwise become a path segment.
        url = marketfeed.candles_url("http://exchange:8090", "EUR/USD", now_ms=0)
        self.assertIn("symbol=EUR%2FUSD", url)

    def test_start_never_goes_negative(self):
        url = marketfeed.candles_url("http://x", "ARVX", window_ms=10_000, now_ms=5_000)
        self.assertIn("start=0", url)


class FetchTest(unittest.TestCase):
    def test_fetches_and_parses_through_an_injected_fetcher(self):
        seen = {}

        def fetcher(url, timeout):
            seen["url"] = url
            seen["timeout"] = timeout
            return CANDLE_RESPONSE

        histogram = marketfeed.fetch_volume_by_price("http://exchange:8090", "ARVX", fetcher=fetcher)
        self.assertEqual(3, len(histogram))
        self.assertIn("symbol=ARVX", seen["url"])
        self.assertEqual(marketfeed.DEFAULT_TIMEOUT_S, seen["timeout"])

    def test_unusable_content_is_empty_rather_than_an_error(self):
        histogram = marketfeed.fetch_volume_by_price(
            "http://exchange:8090", "ARVX", fetcher=lambda url, timeout: "<html>nope</html>"
        )
        self.assertEqual({}, histogram)

    def test_transport_failures_are_reported_not_swallowed(self):
        # A dead feed must be distinguishable from a quiet market — that is the whole reason patience
        # can be trusted. The caller logs this once; it never reaches a strategy.
        def broken(url, timeout):
            raise marketfeed.MarketFeedError(f"could not read market feed {url}: boom")

        with self.assertRaises(marketfeed.MarketFeedError) as caught:
            marketfeed.fetch_volume_by_price("http://exchange:8090", "ARVX", fetcher=broken)
        self.assertIn("http://exchange:8090/api/candles", str(caught.exception))

    def test_fetch_json_wraps_transport_errors(self):
        # Port 1 on localhost: nothing listens, so this exercises the real urllib path.
        with self.assertRaises(marketfeed.MarketFeedError):
            marketfeed.fetch_json("http://127.0.0.1:1/api/candles", timeout=0.5)


class MarketViewIntegrationTest(unittest.TestCase):
    def test_feed_histogram_makes_bookfish_ready(self):
        market = strategies.MarketView()
        market.set_last_sale("ARVX", Decimal("42.20"))
        market.load_traded_volume("ARVX", marketfeed.volume_by_price(CANDLE_RESPONSE))
        stats = strategies.histogram_stats(market.traded_volume["ARVX"])
        self.assertIsNotNone(stats)
        self.assertEqual(120.0, stats.total_weight)

    def test_a_later_read_replaces_rather_than_accumulates(self):
        market = strategies.MarketView()
        market.load_traded_volume("ARVX", {Decimal("42.1"): Decimal("60")})
        market.load_traded_volume("ARVX", {Decimal("42.1"): Decimal("70")})
        self.assertEqual({Decimal("42.1"): Decimal("70")}, market.traded_volume["ARVX"])

    def test_an_empty_read_leaves_the_previous_view_alone(self):
        market = strategies.MarketView()
        market.load_traded_volume("ARVX", {Decimal("42.1"): Decimal("60")})
        market.load_traded_volume("ARVX", {})
        self.assertEqual({Decimal("42.1"): Decimal("60")}, market.traded_volume["ARVX"])


if __name__ == "__main__":
    unittest.main()
