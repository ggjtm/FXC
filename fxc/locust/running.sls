{% from 'fxc/locust/map.jinja' import locust with context %}
{#- Cross-role sls requisites only exist when this minion also carries that role (all-in-one):
    an sls absent from the run is a hard compile error, not an inert ordering hint — see
    fxc/docs/PROBLEMS.md P13. Split-topology cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

fxc-locust-running:
  service.running:
    - name: {{ locust.service_name }}
    - enable: true
    - require:
      - sls: fxc.locust.installed
{% if 'broker' in roles %}
      - sls: fxc.broker.running
{% endif %}
    - watch:
      - file: fxc-locust-unit
