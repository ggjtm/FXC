"""
Execution module for FxcInvestor agent control. Unlike fxc_exchange/fxc_broker, FxcInvestor has no
REST control surface — agent.enabled is a startup-only conf/system-property (FxcInvestor/conf/
fxcinvestor.conf), not a live switch — so enable_agent()/disable_agent() render a systemd drop-in
overriding it and bounce the service. Heavier than the exchange/broker toggles because the app
itself has no live switch, not because Salt can't do better.

reset_hard() is the LOCAL half of scripts/reset.sh --hard: this minion's GridGain work
directory, if one happens to be colocated (an all-in-one topology minion running exchange/
broker/pub alongside investor). The MariaDB-side archive truncation lives in
fxc_mariadb.truncate_archives(); a real fleet-wide reset spanning multiple minions is
fxc/orchestrate/reset_hard.sls — a single execution-module function cannot itself reach other
minions, which is exactly what orchestration runners are for.
"""
import os

import salt.utils.data

__virtualname__ = "fxc_investor"

_DROPIN_DIR = "/etc/systemd/system/fxcinvestor.service.d"
_DROPIN_FILE = _DROPIN_DIR + "/override.conf"


def __virtual__():
    return __virtualname__


def _set_agent_enabled(enabled):
    if not os.path.isdir(_DROPIN_DIR):
        os.makedirs(_DROPIN_DIR)
    with open(_DROPIN_FILE, "w") as fh:
        fh.write("[Service]\n")
        fh.write(
            "Environment=JAVA_TOOL_OPTIONS=-Dagent.enabled={0}\n".format(
                "true" if enabled else "false"
            )
        )
    __salt__["service.systemctl_reload"]()
    __salt__["service.restart"]("fxcinvestor")
    return {"agent.enabled": enabled}


def enable_agent():
    """Bounce the FxcInvestor service with -Dagent.enabled=true."""
    return _set_agent_enabled(True)


def disable_agent():
    """Bounce the FxcInvestor service with -Dagent.enabled=false."""
    return _set_agent_enabled(False)


def reset_hard():
    """
    Remove this minion's GridGain work directories, if any are colocated here (see module
    docstring). Mirrors scripts/reset.sh's GridGain-work-dir cleanup, minion-local only.
    """
    removed = []
    for name in ("exchange", "broker", "pub"):
        # __pillar__ direct read — Salt 3008's pillar_mask_output masks __salt__['pillar.get']
        # strings outside state rendering (fxc/docs/PROBLEMS.md P21).
        work_dir = salt.utils.data.traverse_dict_and_list(
            __pillar__,
            "fxc:{0}:gridgain_work_dir".format(name),
            "/tmp/fxc-{0}-ignite".format(name),
            delimiter=":",
        )
        if os.path.isdir(work_dir):
            __salt__["file.remove"](work_dir)
            removed.append(work_dir)
    return {"removed": removed}
