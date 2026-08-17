{% from 'fxc/exchange/map.jinja' import exchange with context %}
{#- Cross-role sls requisites only exist when this minion also carries that role (all-in-one):
    an sls absent from the run is a hard compile error, not an inert ordering hint — see
    fxc/docs/PROBLEMS.md P13. Split-topology cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

fxc-exchange-running:
  service.running:
    - name: {{ exchange.service_name }}
    - enable: true
    - require:
      - sls: fxc.exchange.installed
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
    - watch:
      - file: fxc-exchange-conf
      - file: fxc-exchange-unit

{#- "started" is not "actually ready" (fxc/docs/PROBLEMS.md) — matches scripts/demo.sh's
    wait_for_log "FxcExchange started" + port waits. #}
fxc-exchange-ready:
  cmd.run:
    - name: journalctl -u {{ exchange.service_name }} --no-pager | grep -q 'FxcExchange started'
    - require:
      - service: fxc-exchange-running
    - retry:
        attempts: 60
        interval: 3
