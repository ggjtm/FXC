{#- Native mariadb-server (replaces the mariadb docker-compose service). Salt's built-in
    mysql_database/mysql_user/mysql_grants states replace docker/mariadb/init/01-databases.sql's
    bootstrap SQL one-for-one. Requires fxc:mariadb:root_password / app_password / tigase_password
    in pillar — see pillar.example/fxc/mariadb.sls. #}
{% from 'fxc/mariadb/map.jinja' import mariadb with context %}

fxc-mariadb-server-pkg:
  pkg.installed:
    - names:
      - {{ mariadb.pkg_name }}
      - {{ mariadb.python_pkg }}

{#- The mysql_* state modules left salt core in 3008 (Great Module Migration → saltext-mysql),
    and bootstrap-installed (onedir) minions bundle their own Python which cannot import the
    distro {{ mariadb.python_pkg }} either — both make every mysql_* state below fail as
    "not found" (fxc/docs/PROBLEMS.md P10). Install the saltext and the driver into the minion's
    own interpreter and reload salt's modules; a no-op wherever both already import. #}
fxc-mariadb-minion-pymysql:
  cmd.run:
    - name: {{ grains['pythonexecutable'] }} -m pip install --quiet pymysql saltext-mysql
    - unless: {{ grains['pythonexecutable'] }} -c 'import pymysql, saltext.mysql'
    - reload_modules: true
    - require:
      - pkg: fxc-mariadb-server-pkg

fxc-mariadb-running-early:
  service.running:
    - name: {{ mariadb.service_name }}
    - enable: true
    - require:
      - pkg: fxc-mariadb-server-pkg

{#- Salt's mysql execution/state modules read connection info from these pillar-mapped keys
    (or ~/.my.cnf); root password is set idempotently the first time only. #}
fxc-mariadb-root-password:
  mysql_user.present:
    - name: root
    - host: localhost
    - password: {{ salt['pillar.get']('fxc:mariadb:root_password') }}
    - connection_user: root
    - connection_pass: {{ salt['pillar.get']('fxc:mariadb:root_password') }}
    - connection_unix_socket: /var/run/mysqld/mysqld.sock
    - require:
      - service: fxc-mariadb-running-early
      - cmd: fxc-mariadb-minion-pymysql

{% for db in mariadb.databases %}
fxc-mariadb-db-{{ db }}:
  mysql_database.present:
    - name: {{ db }}
    - character_set: utf8mb4
    - collate: utf8mb4_unicode_ci
    - require:
      - mysql_user: fxc-mariadb-root-password
{% endfor %}

fxc-mariadb-app-user:
  mysql_user.present:
    - name: {{ mariadb.app_user }}
    - host: '%'
    - password: {{ salt['pillar.get']('fxc:mariadb:app_password') }}
    - require:
      - mysql_user: fxc-mariadb-root-password

{% for db in mariadb.databases if db != 'tigasedb' %}
fxc-mariadb-app-grant-{{ db }}:
  mysql_grants.present:
    - grant: all privileges
    - database: {{ db }}.*
    - user: {{ mariadb.app_user }}
    - host: '%'
    - require:
      - mysql_user: fxc-mariadb-app-user
      - mysql_database: fxc-mariadb-db-{{ db }}
{% endfor %}

fxc-mariadb-tigase-user:
  mysql_user.present:
    - name: {{ mariadb.tigase_user }}
    - host: '%'
    - password: {{ salt['pillar.get']('fxc:mariadb:tigase_password') }}
    - require:
      - mysql_user: fxc-mariadb-root-password

fxc-mariadb-tigase-grant:
  mysql_grants.present:
    - grant: all privileges
    - database: tigasedb.*
    - user: {{ mariadb.tigase_user }}
    - host: '%'
    - require:
      - mysql_user: fxc-mariadb-tigase-user
      - mysql_database: fxc-mariadb-db-tigasedb

fxc-mariadb-bind-address:
  file.replace:
    - name: /etc/mysql/mariadb.conf.d/50-server.cnf
    - pattern: '^bind-address\s*=.*$'
    - repl: 'bind-address = {{ mariadb.bind_address }}'
    - append_if_not_found: true
    - onlyif: test -f /etc/mysql/mariadb.conf.d/50-server.cnf
    - watch_in:
      - service: fxc-mariadb-running-early
