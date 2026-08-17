{% from 'fxc/tigase/map.jinja' import tigase with context %}
{#- Cross-role sls requisites only exist when this minion also carries that role (all-in-one):
    an sls absent from the run is a hard compile error, not an inert ordering hint — see
    fxc/docs/PROBLEMS.md P13. Split-topology cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

{#- "started" is not "actually ready" (fxc/docs/PROBLEMS.md): Tigase opens its ports well before it
    can serve a stream (scripts/demo.sh's wait_for_tigase_ready comment). This retries the log-grep
    demo.sh uses, as the Salt-idiomatic equivalent, before declaring the state converged. #}
fxc-tigase-running:
  service.running:
    - name: {{ tigase.service_name }}
    - enable: true
    - require:
      - sls: fxc.tigase.installed
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
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
