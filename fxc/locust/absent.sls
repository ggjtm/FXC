{% from 'fxc/locust/map.jinja' import locust with context %}

fxc-locust-disabled:
  service.dead:
    - name: {{ locust.service_name }}
    - enable: false

fxc-locust-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ locust.service_name }}.service
    - require:
      - service: fxc-locust-disabled

fxc-locust-dir-absent:
  file.absent:
    - name: {{ locust.install_dir }}
    - require:
      - service: fxc-locust-disabled
