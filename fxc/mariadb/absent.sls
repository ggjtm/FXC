{% from 'fxc/mariadb/map.jinja' import mariadb with context %}

fxc-mariadb-disabled:
  service.dead:
    - name: {{ mariadb.service_name }}
    - enable: false

fxc-mariadb-pkg-removed:
  pkg.removed:
    - names:
      - {{ mariadb.pkg_name }}
    - require:
      - service: fxc-mariadb-disabled
