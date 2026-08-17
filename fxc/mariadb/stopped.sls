{% from 'fxc/mariadb/map.jinja' import mariadb with context %}

fxc-mariadb-stopped:
  service.dead:
    - name: {{ mariadb.service_name }}
