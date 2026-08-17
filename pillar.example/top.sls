# Example pillar top file. Real deployments normally set `roles` as a GRAIN at salt-cloud
# instance-creation time (see cloud.profiles.d/fxc-profiles.conf) rather than via pillar, so this
# file matters mainly for hand-provisioned/non-cloud minions, matched by minion-ID glob below.
#
# `fxc.<component>` pillar files hold the real (secret-bearing, per-environment) configuration and
# are matched onto every minion regardless of role — each .sls only fills in keys the minion's own
# `roles`-selected states actually read; the rest is inert. Copy this whole tree into a real
# (private) pillar_roots/git pillar repo and fill in the placeholder values before use.
base:
  '*':
    - fxc.common
    - fxc.mariadb
    - fxc.tigase
    - fxc.exchange
    - fxc.pub
    - fxc.broker
    - fxc.investor
    - fxc.locust
    - fxc.artifact_repo

  'fxc-demo-*':
    - topology.all-in-one
  'fxc-exchange-*':
    - topology.single-role-exchange
  'fxc-broker-*':
    - topology.single-role-broker
  'fxc-pub-*':
    - topology.single-role-pub
  'fxc-investor-*':
    - topology.single-role-investor
  'fxc-mariadb-*':
    - topology.single-role-mariadb
  'fxc-tigase-*':
    - topology.single-role-tigase
