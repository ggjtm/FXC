{% from 'fxc/broker/map.jinja' import broker with context %}

fxc-broker-running:
  service.running:
    - name: {{ broker.service_name }}
    - enable: true
    - require:
      - sls: fxc.broker.installed
      - sls: fxc.exchange.running
      - sls: fxc.pub.running
      - sls: fxc.mariadb.running
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
