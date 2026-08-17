{% from 'fxc/tigase/map.jinja' import tigase with context %}

{#- "started" is not "actually ready" (fxc/docs/PROBLEMS.md): Tigase opens its ports well before it
    can serve a stream (scripts/demo.sh's wait_for_tigase_ready comment). This retries the log-grep
    demo.sh uses, as the Salt-idiomatic equivalent, before declaring the state converged. #}
fxc-tigase-running:
  service.running:
    - name: {{ tigase.service_name }}
    - enable: true
    - require:
      - sls: fxc.tigase.installed
      - sls: fxc.mariadb.running
    - watch:
      - file: fxc-tigase-config
      - file: fxc-tigase-unit

fxc-tigase-ready:
  cmd.run:
    - name: journalctl -u {{ tigase.service_name }} --no-pager | grep -q 'Server finished starting up'
    - require:
      - service: fxc-tigase-running
    - retry:
        attempts: 60
        interval: 3
