{% from 'fxc/broker/map.jinja' import broker with context %}

fxc-broker-stopped:
  service.dead:
    - name: {{ broker.service_name }}
