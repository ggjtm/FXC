{% from 'fxc/locust/map.jinja' import locust with context %}

fxc-locust-stopped:
  service.dead:
    - name: {{ locust.service_name }}
