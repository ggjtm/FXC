"""How many virtual investors of each strategy to run (FxcInvestor/docs/stories/007).

The harness used to run **one** strategy per process (``--strategy``), which the retired Gatling
simulation did not: it bucketed users by ``Math.floorMod(uid, 100)`` against ``sim.mix.rando`` /
``sim.mix.booker``. This module is the replacement, and it is deliberately separate from
``locustfile.py`` so it can be tested without installing locust.

Two decisions worth knowing:

* **Shares, not counts.** The UI's own *Number of users* field stays the total — it is the knob
  DESIGN §6.5 exists to preserve — and these three numbers apportion it. Shares ``1/4/2`` with 14
  users is 2/8/4; with 7 users it is 1/4/2.
* **A requested type always runs.** A share that apportions to zero borrows a user from the largest
  holder, so a lopsided mix cannot silently delete a strategy from the run. If the total is smaller
  than the number of requested types, the ones that do not fit are **reported** rather than dropped
  quietly (``apportion`` returns them; the caller logs them).

The apportionment here is authoritative rather than Locust's own dispatcher, which cannot express a
live mix (see docs/PROBLEMS.md P16): the harness runs **one** user class whose strategy is an
attribute, and :func:`reassign` moves the minimum number of live investors from one strategy to
another. Locust keeps owning the population size; this owns what each member does.
"""

from __future__ import annotations

from collections import Counter
from collections.abc import Sequence

__all__ = ["STRATEGY_ORDER", "resolve_shares", "apportion", "reassign", "format_mix"]

#: Fixed order — the tie-break for equal remainders, and the order shares are filled in when the
#: user count cannot cover every requested type. Stable so a run is reproducible.
STRATEGY_ORDER: tuple[str, ...] = ("rando", "booker", "bookfish")


def resolve_shares(
    rando: int, booker: int, bookfish: int, strategy: str = ""
) -> dict[str, int]:
    """Resolve the three ``--mix-*`` options (and the ``--strategy`` shorthand) into shares.

    Precedence, and why:

    1. Any **non-zero** ``--mix-*`` value wins outright. Explicit beats implicit, and it lets the
       operator zero a strategy out (``--mix-rando 0``) without having to name the other two.
    2. Otherwise an explicit ``--strategy`` means *all users of that one type*. This is the
       backwards-compatible path: ``scripts/loadtest.sh --strategy bookfish`` and
       ``FXC_STRATEGY=booker`` predate the mix and still mean what they used to.
    3. Otherwise **one of each** — the default, so a bare run exercises all three strategies and
       the split metrics have something to show.

    Zero shares are kept out of the result entirely; a strategy with no share is not in the run.
    """
    shares = {"rando": int(rando), "booker": int(booker), "bookfish": int(bookfish)}
    negative = sorted(name for name, share in shares.items() if share < 0)
    if negative:
        raise ValueError(f"mix shares must not be negative: {', '.join(negative)}")

    explicit = {name: share for name, share in shares.items() if share > 0}
    if explicit:
        return {name: explicit[name] for name in STRATEGY_ORDER if name in explicit}

    name = (strategy or "").strip().lower()
    if name:
        if name not in STRATEGY_ORDER:
            raise ValueError(
                f"unknown strategy: {strategy} (available: {', '.join(STRATEGY_ORDER)})"
            )
        return {name: 1}

    return {name: 1 for name in STRATEGY_ORDER}


def apportion(total: int, shares: dict[str, int]) -> tuple[dict[str, int], list[str]]:
    """Split ``total`` users across ``shares``, giving every requested type at least one.

    Returns ``(counts, dropped)``. ``counts`` contains only strategies that get at least one user and
    sums to ``total`` whenever anything can run; ``dropped`` names the requested strategies that did
    not fit, for the caller to log — a silently truncated mix reads as "all three are running" when
    it isn't.

    Method: Hare quota + largest remainder (integer arithmetic, ties broken by
    :data:`STRATEGY_ORDER`), then **repair any zero** by borrowing a user from the largest count.
    Proportional first, floor second, in that order deliberately: it keeps shares behaving like
    counts when the total matches their sum — shares ``4/10/2`` with 16 users really is 4/10/2 —
    while still guaranteeing a requested strategy is never silently absent.
    """
    requested = [name for name in STRATEGY_ORDER if shares.get(name, 0) > 0]
    if total <= 0 or not requested:
        return {}, list(requested)

    if total < len(requested):
        # Not enough users for one of each: fill by descending share, then by STRATEGY_ORDER.
        ranked = sorted(requested, key=lambda name: (-shares[name], STRATEGY_ORDER.index(name)))
        kept = ranked[:total]
        counts = {name: 1 for name in STRATEGY_ORDER if name in kept}
        return counts, [name for name in requested if name not in kept]

    weight_total = sum(shares[name] for name in requested)
    # Integer arithmetic throughout: divmod's remainder *is* the largest-remainder key, so there is
    # no float rounding to argue about when two shares are close.
    counts: dict[str, int] = {}
    remainders: list[tuple[int, int, str]] = []
    allocated = 0
    for name in requested:
        whole, rem = divmod(total * shares[name], weight_total)
        counts[name] = whole
        allocated += whole
        remainders.append((rem, STRATEGY_ORDER.index(name), name))
    for _, _, name in sorted(remainders, key=lambda item: (-item[0], item[1]))[: total - allocated]:
        counts[name] += 1

    for name in requested:
        if counts[name]:
            continue
        # Borrow from the largest holder — it can spare a user, and a strategy the operator asked
        # for must actually appear in the run.
        donor = max(requested, key=lambda other: (counts[other], -STRATEGY_ORDER.index(other)))
        if counts[donor] < 2:
            break
        counts[donor] -= 1
        counts[name] = 1

    return {name: counts[name] for name in STRATEGY_ORDER if counts.get(name, 0) > 0}, []


def reassign(current: Sequence[str | None], targets: dict[str, int]) -> list[str | None]:
    """Move as few investors as possible from one strategy to another to reach ``targets``.

    ``current`` is one entry per live investor, oldest first (``None`` for one that has not been
    assigned yet — a user locust has just spawned). The return value is the same length, so the caller
    applies it positionally.

    **Minimum churn, oldest kept.** Only investors whose strategy is over target are moved, newest
    first, and unassigned slots are filled before any reassignment happens. So growing the population
    leaves everyone alone, and a ramp-down that happened to kill the wrong strategies is corrected by
    reassigning whoever remains rather than by churning the whole pool. An investor that changes
    strategy keeps its account, its RNG and its portfolio view; only its decision function changes.

    Idempotent: applying it twice changes nothing the second time, which is what lets the caller run it
    on a timer and log only when something actually moved.
    """
    result: list[str | None] = list(current)
    counts = Counter(name for name in result if name is not None)

    # What each strategy still needs, in canonical order so the outcome is deterministic.
    wanted: list[str] = []
    for name in STRATEGY_ORDER:
        wanted.extend([name] * max(0, targets.get(name, 0) - counts.get(name, 0)))
    if not wanted:
        return result

    # Fill the unassigned slots first (oldest first): a new investor costs nothing to place.
    for index, name in enumerate(result):
        if name is None and wanted:
            result[index] = wanted.pop(0)
            counts[result[index]] += 1

    # Then take from whoever is over target, newest first.
    for index in range(len(result) - 1, -1, -1):
        if not wanted:
            break
        name = result[index]
        if name is None:
            continue
        if counts[name] > targets.get(name, 0):
            counts[name] -= 1
            result[index] = wanted.pop(0)
            counts[result[index]] += 1

    return result


def format_mix(counts: dict[str, int]) -> str:
    """``"rando=2 booker=8 bookfish=4"`` — one shape for every log line about the mix."""
    return " ".join(f"{name}={counts[name]}" for name in STRATEGY_ORDER if name in counts)
