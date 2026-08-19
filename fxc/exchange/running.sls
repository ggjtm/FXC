{% from 'fxc/exchange/map.jinja' import exchange with context %}
{#- Cross-role dependencies exist only when this minion also carries that role: an sls
    requisite whose target is absent from the run is a hard compile error, not an inert ordering
    hint (fxc/docs/PROBLEMS.md P13). When the role IS local, include its tree so the requisite
    resolves from any entry point (highstate, orchestrate, single-tree apply); when it isn't,
    cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}
include:
{% if 'mariadb' in roles %}
  - fxc.mariadb
{% endif %}

fxc-exchange-running:
  service.running:
    - name: {{ exchange.service_name }}
    - enable: true
    - require:
      - sls: fxc.exchange.installed
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
    {#- The artifact is watched too: extracting a new build without restarting leaves the old
        code running in memory, which reads as "the deploy did nothing" (P27). #}
    - watch:
      - archive: fxc-exchange-artifact
      - file: fxc-exchange-conf
      - file: fxc-exchange-unit

{#- "started" is not "actually ready" (fxc/docs/PROBLEMS.md) — matches scripts/demo.sh's
    wait_for_log "FxcExchange started" + port waits. is-active guard per P16/P20: journald keeps
    old lines, and Type=simple "active" says nothing — a crash-looping service must not pass. #}
fxc-exchange-ready:
  cmd.run:
    - name: systemctl is-active --quiet {{ exchange.service_name }} && journalctl -u {{ exchange.service_name }} --no-pager | grep -q 'FxcExchange started'
    - require:
      - service: fxc-exchange-running
    - retry:
        attempts: 60
        interval: 3
