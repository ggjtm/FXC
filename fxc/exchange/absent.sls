{% from 'fxc/exchange/map.jinja' import exchange with context %}

fxc-exchange-disabled:
  service.dead:
    - name: {{ exchange.service_name }}
    - enable: false

fxc-exchange-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ exchange.service_name }}.service
    - require:
      - service: fxc-exchange-disabled

fxc-exchange-dir-absent:
  file.absent:
    - name: {{ exchange.install_dir }}
    - require:
      - service: fxc-exchange-disabled
