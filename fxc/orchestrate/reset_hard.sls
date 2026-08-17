# Fleet-wide equivalent of `scripts/reset.sh --hard`, spanning the mariadb minion (archive
# truncation) and the exchange/broker/pub/investor minions (GridGain work-dir cleanup). A single
# execution-module function cannot itself reach across minions — see _modules/fxc_investor.py's
# module docstring — so this is the actual entrypoint:
#
#   salt-run state.orchestrate fxc.orchestrate.reset_hard
#
# Refuses nothing by itself (unlike scripts/reset.sh, which checks for live component processes/
# ports first) — stop the fleet's services before running this, or extend this target with a
# salt.function step calling each role's `fxc_<component>.status` first if that guard matters to
# your environment.

truncate-mariadb-archives:
  salt.function:
    - name: fxc_mariadb.truncate_archives
    - tgt: 'roles:mariadb'
    - tgt_type: grain

remove-gridgain-workdirs:
  salt.function:
    - name: fxc_investor.reset_hard
    - tgt: 'G@roles:exchange or G@roles:broker or G@roles:pub or G@roles:investor'
    - tgt_type: compound
