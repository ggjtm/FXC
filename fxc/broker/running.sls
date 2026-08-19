{% from 'fxc/broker/map.jinja' import broker with context %}
{#- Cross-role dependencies exist only when this minion also carries that role: an sls
    requisite whose target is absent from the run is a hard compile error, not an inert ordering
    hint (fxc/docs/PROBLEMS.md P13). When the role IS local, include its tree so the requisite
    resolves from any entry point (highstate, orchestrate, single-tree apply); when it isn't,
    cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}
include:
{% if 'exchange' in roles %}
  - fxc.exchange
{% endif %}
{% if 'pub' in roles %}
  - fxc.pub
{% endif %}
{% if 'mariadb' in roles %}
  - fxc.mariadb
{% endif %}

fxc-broker-running:
  service.running:
    - name: {{ broker.service_name }}
    - enable: true
    - require:
      - sls: fxc.broker.installed
{% if 'exchange' in roles %}
      - sls: fxc.exchange.running
{% endif %}
{% if 'pub' in roles %}
      - sls: fxc.pub.running
{% endif %}
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
    {#- The artifact is watched too: extracting a new build without restarting leaves the old
        code running in memory, which reads as "the deploy did nothing" (P27). #}
    - watch:
      - archive: fxc-broker-artifact
      - file: fxc-broker-conf
      - file: fxc-broker-unit

{#- is-active guard per P16/P20 — a stale journald line from a crash-looping service must not pass. #}
fxc-broker-ready:
  cmd.run:
    - name: systemctl is-active --quiet {{ broker.service_name }} && journalctl -u {{ broker.service_name }} --no-pager | grep -q 'FxcBroker started'
    - require:
      - service: fxc-broker-running
    - retry:
        attempts: 60
        interval: 3
