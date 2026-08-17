# States-side top file for the fxc formula: `roles` GRAIN list membership -> fxc.<component>
# state trees. `match: grain` against `roles:<value>` checks list membership, so one minion's
# `roles: [exchange, broker]` matches both entries — this is what lets the same top.sls serve the
# split (one role per minion) and all-in-one topologies, purely by changing grain/pillar data, not
# this file.
#
# `roles` is set as a GRAIN, matching cloud.profiles.d/fxc-profiles.conf's `minion: {grains:
# {roles: [...]}}` (the default, salt-cloud-created-minion path). For hand-provisioned minions
# without salt-cloud, set the same grain manually (a static /etc/salt/grains file, or
# `salt-call grains.setval roles "['exchange']"`) using pillar.example/topology/*.sls as the
# human-readable reference for which roles each minion class carries.
#
# If you instead prefer pillar-driven roles everywhere (pillar.example/top.sls's approach), change
# every `match: grain` below to `match: pillar` — the two are not meant to be mixed in one tree.
# See fxc/docs/DESIGN.md.
base:
  '*':
    - fxc.common.installed

  'roles:mariadb':
    - match: grain
    - fxc.mariadb

  'roles:tigase':
    - match: grain
    - fxc.tigase

  'roles:exchange':
    - match: grain
    - fxc.exchange

  'roles:pub':
    - match: grain
    - fxc.pub

  'roles:broker':
    - match: grain
    - fxc.broker

  'roles:investor':
    - match: grain
    - fxc.investor

  'roles:locust':
    - match: grain
    - fxc.locust

  # The salt-master itself, enrolled as a hand-provisioned minion (static /etc/salt/grains) —
  # serves the component artifact tarballs over HTTPS (fxc/docs/PROBLEMS.md P6).
  'roles:artifact-repo':
    - match: grain
    - fxc.artifact_repo
