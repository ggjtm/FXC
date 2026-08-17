{% from 'fxc/pub/map.jinja' import pub with context %}
{#- Cross-role dependencies exist only when this minion also carries that role: an sls
    requisite whose target is absent from the run is a hard compile error, not an inert ordering
    hint (fxc/docs/PROBLEMS.md P13). When the role IS local, include its tree so the requisite
    resolves from any entry point (highstate, orchestrate, single-tree apply); when it isn't,
    cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}
include:
{% if 'tigase' in roles %}
  - fxc.tigase
{% endif %}
{% if 'mariadb' in roles %}
  - fxc.mariadb
{% endif %}

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
