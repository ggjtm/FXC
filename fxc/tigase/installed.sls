{#- Native Tigase: fetch the same vendor dist tarball docker/tigase/Dockerfile builds from, render
    config.tdsl + the schema-load script from pillar-driven MariaDB connection info, and install a
    systemd unit. Requires fxc.mariadb.running (schema load needs a live MariaDB) and installs its
    own JDK 17 (fxc.common.jdk17), never jdk21. #}
{% from 'fxc/tigase/map.jinja' import tigase, jdk17_home with context %}
{% from 'fxc/map.jinja' import fxc as common with context %}

include:
  - fxc.common.installed
  - fxc.common.jdk17

fxc-tigase-curl-pkg:
  pkg.installed:
    - name: curl

fxc-tigase-dirs:
  file.directory:
    - name: {{ tigase.install_dir }}
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-tigase-dist:
  archive.extracted:
    - name: {{ tigase.install_dir }}
    - source: {{ tigase.download_url }}
    - archive_format: tar
    - options: z
    - enforce_toplevel: false
    - if_missing: {{ tigase.install_dir }}/scripts/tigase.sh
    - user: {{ common.service_user }}
    - require:
      - file: fxc-tigase-dirs
      - pkg: fxc-tigase-curl-pkg

fxc-tigase-scripts-executable:
  file.directory:
    - name: {{ tigase.install_dir }}/scripts
    - recurse:
      - mode
    - file_mode: '0750'
    - require:
      - archive: fxc-tigase-dist

fxc-tigase-config:
  file.managed:
    - name: {{ tigase.install_dir }}/etc/config.tdsl
    - source: salt://fxc/tigase/files/config.tdsl.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - context:
        tigase: {{ tigase | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', salt['pillar.get']('fxc:common:mariadb_host', 'localhost')) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', 3306) }}
        tigase_user: {{ salt['pillar.get']('fxc:mariadb:tigase_user', 'tigase') | tojson }}
        tigase_password: {{ salt['pillar.get']('fxc:mariadb:tigase_password') | tojson }}
    - require:
      - archive: fxc-tigase-dist

fxc-tigase-load-schema-script:
  file.managed:
    - name: {{ tigase.install_dir }}/load-schema.sh
    - source: salt://fxc/tigase/files/load-schema.sh.jinja
    - template: jinja
    - mode: '0750'
    - user: {{ common.service_user }}
    - context:
        tigase: {{ tigase | tojson }}
        svc_password: {{ salt['pillar.get']('fxc:tigase:svc_password') | tojson }}
    - require:
      - archive: fxc-tigase-dist

{#- Schema load talks to MariaDB and provisions XMPP service accounts. Marker-guarded so a later
    highstate doesn't re-run it (init-schema.sh's own idempotency story is a warning, not a hard
    guarantee — see its comment) — requires the mariadb role's minion to already be converged. #}
fxc-tigase-schema-loaded:
  cmd.run:
    - name: {{ tigase.install_dir }}/load-schema.sh && touch {{ tigase.install_dir }}/.schema-loaded
    - runas: {{ common.service_user }}
    - unless: test -f {{ tigase.install_dir }}/.schema-loaded
    - require:
      - file: fxc-tigase-load-schema-script
      - sls: fxc.mariadb.running

fxc-tigase-unit:
  file.managed:
    - name: /etc/systemd/system/{{ tigase.service_name }}.service
    - source: salt://fxc/tigase/files/fxctigase.service.jinja
    - template: jinja
    - context:
        tigase: {{ tigase | tojson }}
        common: {{ common | tojson }}
        jdk17_home: {{ jdk17_home | tojson }}
    - require:
      - archive: fxc-tigase-dist

fxc-tigase-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-tigase-unit
