{% from 'fxc/pub/map.jinja' import pub with context %}
{#- Cross-role sls requisites only exist when this minion also carries that role (all-in-one):
    an sls absent from the run is a hard compile error, not an inert ordering hint — see
    fxc/docs/PROBLEMS.md P13. Split-topology cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

fxc-pub-running:
  service.running:
    - name: {{ pub.service_name }}
    - enable: true
    - require:
      - sls: fxc.pub.installed
{% if 'tigase' in roles %}
      - sls: fxc.tigase.running
{% endif %}
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
    - watch:
      - file: fxc-pub-conf
      - file: fxc-pub-unit
