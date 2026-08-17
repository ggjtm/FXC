"""
Execution module for FxcExchange operator actions: open/close the trading session and clear the
order book — the REST equivalent of scripts/demo.sh's market_state()/open_market() shell
functions. Deliberately NOT folded into fxc.exchange.running: session.startClosed=true means the
exchange boots halted on purpose (FxcExchange/conf/fxcexchange.conf), so opening the market stays
a separate, deliberate action (see fxc/docs/PROBLEMS.md).

Named `fxc_exchange` rather than `fxc.exchange` because Salt execution modules do not support the
state tree's dotted directory namespacing — a module's callable name is fixed by its filename/
__virtualname__. This is a forced, documented deviation (fxc/docs/DESIGN.md), not an oversight.
"""
import salt.utils.http as http

__virtualname__ = "fxc_exchange"


def __virtual__():
    return __virtualname__


def _base_url():
    port = __salt__["pillar.get"]("fxc:exchange:feed_http_port", 8090)
    host = __salt__["pillar.get"]("fxc:exchange:local_host", "127.0.0.1")
    return "http://{0}:{1}".format(host, port)


def status():
    """
    Return the exchange's /api/status JSON (marketState, etc). Used by health checks and by
    open()/close() to confirm the change took effect.
    """
    result = http.query(_base_url() + "/api/status", decode=True, decode_type="json")
    return result.get("dict", result)


def open():  # pylint: disable=redefined-builtin
    """
    Open the trading session (POST /api/session/open). Aliased by resume().
    """
    http.query(_base_url() + "/api/session/open", method="POST", status=True)
    return status()


def close():
    """
    Halt the trading session (POST /api/session/halt). Aliased by halt().
    """
    http.query(_base_url() + "/api/session/halt", method="POST", status=True)
    return status()


def halt():
    """Alias for close() — matches the REST verb / console wording ("Halt")."""
    return close()


def resume():
    """Alias for open() — matches the console wording ("Start trading")."""
    return open()


def clear_book(symbol=None):
    """
    Mass-cancel the order book (POST /api/book/clear[?symbol=]). Requires feed.controls.enabled=true
    on the exchange (fxcexchange.conf).
    """
    url = _base_url() + "/api/book/clear"
    if symbol:
        url += "?symbol={0}".format(symbol)
    http.query(url, method="POST", status=True)
    return status()
