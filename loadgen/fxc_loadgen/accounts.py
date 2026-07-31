"""Each virtual investor's own broker account (FxcInvestor/docs/stories/004).

Sharing two dev accounts across sixteen investors makes the broker console's per-account P&L a blend
of whatever happened to be trading — which is exactly what that chart looks like it is *not*. So each
investor opens one:

    POST http://<broker-console>/api/accounts?clientId=locust-3  ->  {"account":"000100042", ...}

**Identity is a slot, not a spawn count.** Locust spawns and kills users on every ramp and every
re-mix, so an ever-increasing index would open a new account per spawn — hundreds per demo, each
funded, most of them idle within a minute. Instead an investor claims the lowest free **slot** on
start and releases it on stop, so a ramp 8 → 16 → 8 with re-mixes in between reuses
``locust-0…locust-15``. The broker returns the same account for a client id it has seen, so the
population of accounts settles at the high-water mark of concurrent investors.

**Never fatal.** A broker with opening switched off, an unreachable console, an unparseable reply:
all of them mean falling back to the shared ``--accounts`` list. Losing a *private* account should not
stop the harness generating load — it just makes the P&L less interesting.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

__all__ = ["AccountError", "SlotPool", "AccountRegistry", "open_account", "accounts_url",
           "DEFAULT_PREFIX", "DEFAULT_TIMEOUT_S"]

DEFAULT_PREFIX = "locust"

#: Short: this runs once per investor at spawn, inline with the ramp.
DEFAULT_TIMEOUT_S = 5.0


class AccountError(RuntimeError):
    """The broker would not, or could not, give this client an account."""


class SlotPool:
    """Hands out the lowest free slot number, and takes it back.

    Deliberately not a counter: the point is that slot 3 released by a dying investor is slot 3 again
    for the next one, so the set of client ids — and therefore of accounts — stays bounded by how many
    investors run at once rather than by how many have ever run.
    """

    def __init__(self) -> None:
        self._in_use: set[int] = set()

    def claim(self) -> int:
        slot = 0
        while slot in self._in_use:
            slot += 1
        self._in_use.add(slot)
        return slot

    def release(self, slot: int) -> None:
        self._in_use.discard(slot)

    def in_use(self) -> int:
        return len(self._in_use)


def accounts_url(console_url: str) -> str:
    return f"{console_url.rstrip('/')}/api/accounts"


def open_account(
    console_url: str,
    client_id: str,
    owner_name: str | None = None,
    timeout: float = DEFAULT_TIMEOUT_S,
    opener=None,
) -> str:
    """Open (or re-find) ``client_id``'s account and return its number.

    Query parameters with an empty body, because that is what the broker's POST endpoints take — the
    Java side has a JSON writer and deliberately no parser, so requests carry no JSON. The *response*
    is JSON, which Python can parse without argument.

    Raises :class:`AccountError` for anything that goes wrong; the caller decides whether that is
    worth failing over.
    """
    query = {"clientId": client_id}
    if owner_name:
        query["ownerName"] = owner_name
    url = f"{accounts_url(console_url)}?{urllib.parse.urlencode(query)}"
    request = urllib.request.Request(url, data=b"", method="POST")
    try:
        with (opener or urllib.request.urlopen)(request, timeout=timeout) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        # The broker declining (409 disabled, 400 bad client id) is a different thing from the broker
        # being unreachable, and the message it sends says which.
        detail = ""
        try:
            detail = error.read().decode("utf-8", "replace").strip()
        except Exception:  # noqa: BLE001 - never let error handling raise
            pass
        raise AccountError(f"broker refused to open an account (HTTP {error.code}): {detail}") from error
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise AccountError(f"could not reach the broker console at {console_url}: {error}") from error

    try:
        payload = json.loads(body)
        account = payload["account"]
    except (ValueError, TypeError, KeyError) as error:
        raise AccountError(f"broker's reply carried no account: {body!r}") from error
    if not isinstance(account, str) or not account:
        raise AccountError(f"broker returned an unusable account: {account!r}")
    return account


class AccountRegistry:
    """Slots, client ids, and the account each one holds.

    One instance per harness process. Holds the mapping so a respawn into a slot does not need a round
    trip to learn what it already knew — the broker would answer the same thing, but not paying for it
    keeps a ramp fast.
    """

    def __init__(self, console_url: str, prefix: str = DEFAULT_PREFIX,
                 timeout: float = DEFAULT_TIMEOUT_S, opener=None) -> None:
        self.console_url = console_url
        self.prefix = prefix
        self.timeout = timeout
        self._opener = opener
        self._pool = SlotPool()
        self._accounts: dict[str, str] = {}

    def client_id(self, slot: int) -> str:
        return f"{self.prefix}-{slot}"

    def claim(self) -> tuple[int, str]:
        """Claim a slot and return it with the account for its client id.

        :raises AccountError: leaving the slot claimed is deliberate — the caller releases it, and a
            slot that stayed claimed on failure would drift the numbering for everyone after it.
        """
        slot = self._pool.claim()
        client_id = self.client_id(slot)
        try:
            account = self._accounts.get(client_id)
            if account is None:
                account = open_account(self.console_url, client_id, f"Investor {client_id}",
                                       timeout=self.timeout, opener=self._opener)
                self._accounts[client_id] = account
            return slot, account
        except AccountError:
            self._pool.release(slot)
            raise

    def release(self, slot: int) -> None:
        """Give the slot back. The account mapping stays: the next holder of this slot is the same
        client, and the broker would hand back the same account anyway."""
        self._pool.release(slot)

    def known_accounts(self) -> dict[str, str]:
        return dict(self._accounts)

    def slots_in_use(self) -> int:
        return self._pool.in_use()
