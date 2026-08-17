{% from 'fxc/broker/map.jinja' import broker with context %}
{#- Cross-role sls requisites only exist when this minion also carries that role (all-in-one):
    an sls absent from the run is a hard compile error, not an inert ordering hint — see
    fxc/docs/PROBLEMS.md P13. Split-topology cross-minion ordering is the orchestrate's job (P2). #}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

fxc-broker-running:
  service.running:
    - name: {{ broker.service_name }}
    - enable: true
    - require:
      - sls: fxc.broker.installed
{% if 'exchange' in roles %}
      - sls: fxc.exchange.running
{% endif %}
{% if 'pub' in roles %}
      - sls: fxc.pub.running
{% endif %}
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}
    - watch:
      - file: fxc-broker-conf
      - file: fxc-broker-unit

fxc-broker-ready:
  cmd.run:
    - name: journalctl -u {{ broker.service_name }} --no-pager | grep -q 'FxcBroker started'
    - require:
      - service: fxc-broker-running
    - retry:
        attempts: 60
        interval: 3
