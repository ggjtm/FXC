"""
Execution module for FxcBroker operator actions: the start/stop-trading gate added in root
docs/PLAN.md Phase 6 story 002 (FxcBroker/docs/stories/002). Account opening (POST /api/accounts)
is deliberately NOT wrapped here — it is a client/demo operation, not fleet management.

See fxc_exchange.py's module docstring for why this is `fxc_broker` and not `fxc.broker`.
"""
import salt.utils.data
import salt.utils.http as http

__virtualname__ = "fxc_broker"


def __virtual__():
    return __virtualname__


def _pillar_get(key, default=None):
    """__pillar__ direct read — Salt 3008's pillar_mask_output masks __salt__['pillar.get']
    strings outside state rendering (fxc/docs/PROBLEMS.md P21, full rationale in fxc_exchange.py)."""
    return salt.utils.data.traverse_dict_and_list(__pillar__, key, default, delimiter=":")


def _base_url():
    port = _pillar_get("fxc:broker:web_http_port", 8083)
    host = _pillar_get("fxc:broker:local_host", "127.0.0.1")
    return "http://{0}:{1}".format(host, port)


def status():
    """Return the broker's /api/status JSON."""
    result = http.query(_base_url() + "/api/status", decode=True, decode_type="json")
    return result.get("dict", result)


def start_trading():
    """POST /api/trading/start — resume order acceptance after stop_trading()."""
    http.query(_base_url() + "/api/trading/start", method="POST", status=True)
    return status()


def stop_trading():
    """POST /api/trading/stop — operator gate; does not affect account opening."""
    http.query(_base_url() + "/api/trading/stop", method="POST", status=True)
    return status()
