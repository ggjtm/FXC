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
    {#- The artifact is watched too: extracting a new build without restarting leaves the old
        code running in memory, which reads as "the deploy did nothing" (P27). #}
    - watch:
      - archive: fxc-pub-artifact
      - file: fxc-pub-conf
      - file: fxc-pub-unit

{#- Pub had NO ready probe, and that gap is exactly how P20 hid: Type=simple start "succeeds"
    before the first crash, so a crash-looping pub converged green and broker/investor/locust
    only failed later on their own requisites. Same probe pattern as exchange/broker (Main.java
    prints "FxcPub started" to stdout → journald), is-active-guarded per P16. #}
fxc-pub-ready:
  cmd.run:
    - name: systemctl is-active --quiet {{ pub.service_name }} && journalctl -u {{ pub.service_name }} --no-pager | grep -q 'FxcPub started'
    - require:
      - service: fxc-pub-running
    - retry:
        attempts: 60
        interval: 3
