"""Market-wide traded volume, read from the exchange's public chart feed (story 007).

``bookfish`` samples the histogram of volume actually traded. The Java agent builds that histogram
from fills on the FxcPub XMPP feed and therefore sees the whole market; this harness has no XMPP
client, so it sees only its own OFX responses — sparse enough that ``patience.py`` correctly refuses to
trade on it. This module closes that gap with the data the exchange already publishes for its chart:

    GET http://<exchange>:8090/api/candles?symbol=ARVX&granularity=1m&start=<ms>
    -> {"symbol":"ARVX", ..., "volumeByPrice":[{"price":42.1,"volume":20}, ...]}

That array *is* a traded-volume histogram, aggregated across every participant — including the fills
the Java agents did, which no OFX response would ever have shown this harness.

**Why this endpoint and not a WebSocket.** The exchange also streams ticks on :8091, but consuming
RFC 6455 would mean hand-rolling a client or adding a dependency; the harness's only dependency is
locust itself. A GET on an interval is enough for a fair-value estimate that ``bookfish`` refreshes
every few seconds anyway.

**Layering.** This is the exchange's *public* market data — the same JSON its own browser console
reads — not an order-entry path. Orders still go only to FxcBroker over OFX. An empty
``--exchange-url`` disables the whole thing, and a failing feed never fails a run: the harness falls
back to its own observations, which is exactly the pre-feed behaviour.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal, InvalidOperation

__all__ = [
    "MarketFeedError",
    "DEFAULT_GRANULARITY",
    "DEFAULT_WINDOW_MS",
    "DEFAULT_TIMEOUT_S",
    "candles_url",
    "volume_by_price",
    "fetch_json",
    "fetch_volume_by_price",
]

#: Candle granularity requested. It does not change ``volumeByPrice`` (which is window-aggregate), but
#: the endpoint requires a valid token and rejects an unknown one with a 400.
DEFAULT_GRANULARITY = "1m"

#: How far back to aggregate. The endpoint defaults to a full day, which would let hours-old prices
#: drag fair value around; fifteen minutes tracks a demo that has been running for a while without
#: being so short that a quiet minute empties the histogram.
DEFAULT_WINDOW_MS = 15 * 60 * 1000

#: Short by design: this is a best-effort side channel, and a slow exchange must not stall the
#: greenlet that polls it.
DEFAULT_TIMEOUT_S = 3.0


class MarketFeedError(RuntimeError):
    """The feed could not be read. Raised for transport faults, never for odd content.

    Deliberately loud rather than swallowed: a silently dead feed looks exactly like a market with no
    volume, which is the one thing ``bookfish``'s patience is not supposed to be uncertain about. The
    caller logs it once and carries on with local observations.
    """


def candles_url(
    base_url: str,
    symbol: str,
    granularity: str = DEFAULT_GRANULARITY,
    window_ms: int = DEFAULT_WINDOW_MS,
    now_ms: int | None = None,
) -> str:
    """Build the candles URL. ``end`` is left to the server (it defaults to *now*)."""
    now = int(time.time() * 1000) if now_ms is None else int(now_ms)
    query = urllib.parse.urlencode(
        {"symbol": symbol, "granularity": granularity, "start": max(0, now - int(window_ms))}
    )
    return f"{base_url.rstrip('/')}/api/candles?{query}"


def volume_by_price(payload: object) -> dict[Decimal, Decimal]:
    """Extract ``{price: volume}`` from a candles response. Tolerant: never raises.

    Anything unexpected — a non-object body, a missing or non-list ``volumeByPrice``, an entry with a
    null price, a zero or negative volume, an unparseable number — is skipped rather than fatal. The
    caller cannot do anything useful with a half-parsed histogram, and an empty one already means "no
    market-wide data", which :meth:`MarketView.load_traded_volume` handles by leaving the previous view
    in place.

    Prices are converted through ``str`` so ``42.1`` becomes ``Decimal("42.1")`` rather than the binary
    float's tail; ``Decimal("42.1") == Decimal("42.10")``, so bins from the feed and bins from OFX
    observations land on the same key.
    """
    if not isinstance(payload, dict):
        return {}
    levels = payload.get("volumeByPrice")
    if not isinstance(levels, list):
        return {}

    histogram: dict[Decimal, Decimal] = {}
    for level in levels:
        if not isinstance(level, dict):
            continue
        try:
            price = Decimal(str(level["price"]))
            volume = Decimal(str(level["volume"]))
        except (KeyError, TypeError, ValueError, InvalidOperation):
            continue
        if not price.is_finite() or not volume.is_finite() or price <= 0 or volume <= 0:
            continue
        histogram[price] = histogram.get(price, Decimal(0)) + volume
    return histogram


def fetch_json(url: str, timeout: float = DEFAULT_TIMEOUT_S) -> object:
    """GET and decode JSON, raising :class:`MarketFeedError` for anything that goes wrong.

    ``urllib`` rather than ``requests``: locust's gevent monkey-patching makes this non-blocking, and
    the harness stays a one-dependency package.
    """
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - fixed scheme
            body = response.read()
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise MarketFeedError(f"could not read market feed {url}: {error}") from error
    try:
        return json.loads(body)
    except (ValueError, TypeError) as error:
        raise MarketFeedError(f"market feed {url} returned a non-JSON body: {error}") from error


def fetch_volume_by_price(
    base_url: str,
    symbol: str,
    granularity: str = DEFAULT_GRANULARITY,
    window_ms: int = DEFAULT_WINDOW_MS,
    timeout: float = DEFAULT_TIMEOUT_S,
    fetcher=fetch_json,
) -> dict[Decimal, Decimal]:
    """Read one symbol's market-wide traded-volume histogram.

    Raises :class:`MarketFeedError` if the feed is unreachable; returns ``{}`` if it answers with
    something unusable. ``fetcher`` is injectable so tests need no socket.
    """
    url = candles_url(base_url, symbol, granularity=granularity, window_ms=window_ms)
    return volume_by_price(fetcher(url, timeout))
