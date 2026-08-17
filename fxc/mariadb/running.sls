{% from 'fxc/mariadb/map.jinja' import mariadb with context %}

fxc-mariadb-running:
  service.running:
    - name: {{ mariadb.service_name }}
    - enable: true
    - require:
      - sls: fxc.mariadb.installed
