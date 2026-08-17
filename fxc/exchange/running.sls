{% from 'fxc/exchange/map.jinja' import exchange with context %}

fxc-exchange-running:
  service.running:
    - name: {{ exchange.service_name }}
    - enable: true
    - require:
      - sls: fxc.exchange.installed
      - sls: fxc.mariadb.running
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
