# Cross-minion sequencing for the split (one-instance-per-role) topology, translating
# scripts/demo.sh's dependency order (MariaDB -> Tigase -> Exchange -> Pub -> Broker ->
# Investor/Locust) into salt.state requisites keyed by role.
#
# Plain `require:` inside a component's running.sls only orders states within one minion's own
# state run (see fxc/docs/PROBLEMS.md) — it cannot block fxc-broker-1's apply on fxc-exchange-1
# actually being up. This orchestrate target is what does that, once the fleet exists and minion
# keys are accepted:
#
#   salt-run state.orchestrate fxc.orchestrate.demo_stack
#
# This converges every minion's stack but deliberately does NOT open the trading session —
# session.startClosed=true is deliberate cold-start behavior (FxcExchange/conf/fxcexchange.conf),
# so opening the market stays a separate, explicit follow-up:
#
#   salt -G 'roles:exchange' fxc_exchange.open
#
# For the all-in-one topology (everything on one minion) this orchestrate target still works, but
# is unnecessary — plain `require:` in fxc/*/running.sls is already sufficient for a single state
# run on a single minion.

mariadb-converge:
  salt.state:
    - tgt: 'roles:mariadb'
    - tgt_type: grain
    - sls: fxc.mariadb

tigase-converge:
  salt.state:
    - tgt: 'roles:tigase'
    - tgt_type: grain
    - sls: fxc.tigase
    - require:
      - salt: mariadb-converge

exchange-converge:
  salt.state:
    - tgt: 'roles:exchange'
    - tgt_type: grain
    - sls: fxc.exchange
    - require:
      - salt: mariadb-converge

pub-converge:
  salt.state:
    - tgt: 'roles:pub'
    - tgt_type: grain
    - sls: fxc.pub
    - require:
      - salt: tigase-converge
      - salt: mariadb-converge

broker-converge:
  salt.state:
    - tgt: 'roles:broker'
    - tgt_type: grain
    - sls: fxc.broker
    - require:
      - salt: exchange-converge
      - salt: pub-converge
      - salt: mariadb-converge

investor-converge:
  salt.state:
    - tgt: 'roles:investor'
    - tgt_type: grain
    - sls: fxc.investor
    - require:
      - salt: broker-converge

locust-converge:
  salt.state:
    - tgt: 'roles:locust'
    - tgt_type: grain
    - sls: fxc.locust
    - require:
      - salt: broker-converge
