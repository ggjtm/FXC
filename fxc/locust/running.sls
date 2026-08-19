{% from 'fxc/locust/map.jinja' import locust with context %}
{#- Cross-role dependencies exist only when this minion also carries that role: an sls
    requisite whose target is absent from the run is a hard compile error, not an inert ordering
    hint (fxc/docs/PROBLEMS.md P13). When the role IS local, include its tree so the requisite
    resolves from any entry point (highstate, orchestrate, single-tree apply); when it isn't,
    cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}
include:
{% if 'broker' in roles %}
  - fxc.broker
{% endif %}

fxc-locust-running:
  service.running:
    - name: {{ locust.service_name }}
    - enable: true
    - require:
      - sls: fxc.locust.installed
{% if 'broker' in roles %}
      - sls: fxc.broker.running
{% endif %}
    {#- The artifact is watched too: extracting a new build without restarting leaves the old
        code running in memory, which reads as "the deploy did nothing" (P27). #}
    - watch:
      - archive: fxc-locust-artifact
      - file: fxc-locust-unit
