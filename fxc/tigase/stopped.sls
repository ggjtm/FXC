{% from 'fxc/tigase/map.jinja' import tigase with context %}

fxc-tigase-stopped:
  service.dead:
    - name: {{ tigase.service_name }}
