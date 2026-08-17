{% from 'fxc/pub/map.jinja' import pub with context %}

fxc-pub-stopped:
  service.dead:
    - name: {{ pub.service_name }}
