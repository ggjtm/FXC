# fxc — SaltStack formula

Deploys FXC (FxcExchange, FxcBroker, FxcPub, FxcInvestor, MariaDB, Tigase, Locust) onto a fleet of
AWS EC2 minions, natively (JDK + systemd, no containers). See [DESIGN.md](DESIGN.md) for the full
design, [PLAN.md](PLAN.md) for status, [PROBLEMS.md](PROBLEMS.md) for known gaps/risks.

This is a separate, native deployment path from the repo's `docker-compose.yml` demo — the two
don't interact.

## Prerequisites

- A Salt master (or masterless minions with `file_client: local`) with this repo's contents
  available as the `fxc` formula (gitfs, or copied into `file_roots`).
- A private pillar tree based on [`pillar.example/`](../../pillar.example) with real secrets filled
  in — see its `README.md`.
- A signed GridGain 8 Ultimate XML license (v2.1), referenced by `fxc:gridgain:license_source`
  pillar key (never committed).
- The pre-built distribution artifacts `fxc:<component>:artifact_url`/`artifact_sha256` point at —
  **does not exist yet**, see `PROBLEMS.md` P6. Without this, `installed.sls` states cannot
  converge for real.
- For the AWS path: `salt-cloud` configured with real credentials (`cloud.providers.d/fxc-aws.conf`
  is a placeholder template) and real AMI IDs / security group IDs
  (`cloud.profiles.d/fxc-profiles.conf`).

## Bring up the fleet (AWS)

```sh
salt-cloud -m cloud.maps.d/fxc-fleet.map          # create the 6 EC2 minions (one per role)
# ... accept minion keys on the master ...
salt-run state.orchestrate fxc.orchestrate.demo_stack   # converge in dependency order
salt -G 'roles:exchange' fxc_exchange.open        # open the market (deliberate, not automatic)
```

Tear down: `salt-cloud -m cloud.maps.d/fxc-fleet.map -d`.

## Local / masterless testing (no AWS)

```sh
salt-call --local state.apply fxc.common.installed test=true
salt-call --local state.apply fxc.mariadb test=true
# ... in dependency order: mariadb, tigase, exchange, pub, broker, investor, locust ...
```

## Operator actions

```sh
salt -G 'roles:exchange' fxc_exchange.status
salt -G 'roles:exchange' fxc_exchange.open       # aliased: resume
salt -G 'roles:exchange' fxc_exchange.close      # aliased: halt
salt -G 'roles:exchange' fxc_exchange.clear_book
salt -G 'roles:broker'   fxc_broker.start_trading
salt -G 'roles:broker'   fxc_broker.stop_trading
salt -G 'roles:investor' fxc_investor.enable_agent
salt -G 'roles:investor' fxc_investor.disable_agent
salt-run state.orchestrate fxc.orchestrate.reset_hard   # fleet-wide scripts/reset.sh --hard equivalent
```

## Choosing a topology

Default is one EC2 instance per role (`cloud.maps.d/fxc-fleet.map`). For an all-in-one demo box,
use the `fxc-all-in-one` profile instead (commented example in the map file) — no `top.sls` or
state changes needed either way; only the `roles` grain/pillar data differs. See `pillar.example/
README.md` for the grain-vs-pillar targeting choice.
