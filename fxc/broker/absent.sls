{% from 'fxc/broker/map.jinja' import broker with context %}

fxc-broker-disabled:
  service.dead:
    - name: {{ broker.service_name }}
    - enable: false

fxc-broker-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ broker.service_name }}.service
    - require:
      - service: fxc-broker-disabled

fxc-broker-dir-absent:
  file.absent:
    - name: {{ broker.install_dir }}
    - require:
      - service: fxc-broker-disabled
