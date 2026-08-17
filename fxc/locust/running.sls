{% from 'fxc/locust/map.jinja' import locust with context %}

fxc-locust-running:
  service.running:
    - name: {{ locust.service_name }}
    - enable: true
    - require:
      - sls: fxc.locust.installed
      - sls: fxc.broker.running
    - watch:
      - file: fxc-locust-unit
