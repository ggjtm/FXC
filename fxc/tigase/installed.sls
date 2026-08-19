{#- Native Tigase: fetch the same vendor dist tarball docker/tigase/Dockerfile builds from, render
    config.tdsl + the schema-load script from pillar-driven MariaDB connection info, and install a
    systemd unit. Requires fxc.mariadb.running (schema load needs a live MariaDB) and installs its
    own JDK 17 (fxc.common.jdk17), never jdk21. #}
{% from 'fxc/tigase/map.jinja' import tigase, jdk17_home with context %}
{% from 'fxc/map.jinja' import fxc as common with context %}
{% set roles = salt['grains.get']('roles', salt['pillar.get']('roles', [])) %}

include:
  - fxc.common.installed
  - fxc.common.jdk17
{% if 'mariadb' in roles %}
  - fxc.mariadb
{% endif %}

{#- Cross-role mariadb dependency handled by the conditional include above — P13. #}
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

{#- source_hash is mandatory for remote archives (P11); --strip-components=1 mirrors
    docker/tigase/Dockerfile — the dist tarball nests everything under
    tigase-server-<build>/, but every path below assumes content directly in install_dir. #}
fxc-tigase-dist:
  archive.extracted:
    - name: {{ tigase.install_dir }}
    - source: {{ tigase.download_url }}
    - source_hash: sha256={{ tigase.dist_sha256 }}
    - archive_format: tar
    - options: z --strip-components=1
    - enforce_toplevel: false
    - if_missing: {{ tigase.install_dir }}/scripts/tigase.sh
    - user: {{ common.service_user }}
    - require:
      - file: fxc-tigase-dirs
      - pkg: fxc-tigase-curl-pkg

{#- archive.extracted does not enforce user/group recursively when `options` is set (the tar runs
    as root and its output stays root-owned) — P11. load-schema.sh sed-patches files inside
    database/ as {{ common.service_user }}, so the whole tree must actually belong to it. #}
fxc-tigase-dist-ownership:
  file.directory:
    - name: {{ tigase.install_dir }}
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - recurse:
      - user
      - group
    - require:
      - archive: fxc-tigase-dist

fxc-tigase-scripts-executable:
  file.directory:
    - name: {{ tigase.install_dir }}/scripts
    - recurse:
      - mode
    - file_mode: '0750'
    - require:
      - archive: fxc-tigase-dist

{#- Tigase rewrites config.tdsl on startup (P4, now verified: alphabetized keys, materialized
    seeOtherHost {} defaults — cosmetically different, same content). Managing config.tdsl
    directly therefore diffs on EVERY converge, and a watch on it bounces a healthy XMPP server
    each highstate. So: render the template to a .salt side file (stable — only pillar/template
    edits change it) and copy it over the live file only onchanges of the render. Tigase's own
    reformatting of the live file never triggers anything; config-as-code changes still land and
    restart via running.sls's watch on the copy. #}
fxc-tigase-config-render:
  file.managed:
    - name: {{ tigase.install_dir }}/etc/config.tdsl.salt
    - source: salt://fxc/tigase/files/config.tdsl.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - context:
        tigase: {{ tigase | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', salt['pillar.get']('fxc:common:mariadb_host', 'localhost')) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', 3306) }}
        tigase_user: {{ salt['pillar.get']('fxc:mariadb:tigase_user', 'tigase') | tojson }}
        tigase_password: {{ salt['pillar.get']('fxc:mariadb:tigase_password') | tojson }}
    - require:
      - archive: fxc-tigase-dist

{#- .config.tdsl.applied snapshots what was last pushed live. Copy (and thus restart, via
    running.sls's watch) only when the fresh render differs from that snapshot or the live file
    is missing — NOT when the live file differs from the render, because it always will once
    Tigase reformats it. #}
fxc-tigase-config:
  cmd.run:
    - name: >
        install -o {{ common.service_user }} -g {{ common.service_group }} -m 0644
        {{ tigase.install_dir }}/etc/config.tdsl.salt {{ tigase.install_dir }}/etc/config.tdsl &&
        cp -p {{ tigase.install_dir }}/etc/config.tdsl.salt {{ tigase.install_dir }}/etc/.config.tdsl.applied
    - unless: >
        test -f {{ tigase.install_dir }}/etc/config.tdsl &&
        cmp -s {{ tigase.install_dir }}/etc/config.tdsl.salt {{ tigase.install_dir }}/etc/.config.tdsl.applied
    - require:
      - file: fxc-tigase-config-render

fxc-tigase-load-schema-script:
  file.managed:
    - name: {{ tigase.install_dir }}/load-schema.sh
    - source: salt://fxc/tigase/files/load-schema.sh.jinja
    - template: jinja
    - mode: '0750'
    - user: {{ common.service_user }}
    {#- group too, not just user: dist-ownership chowns the whole tree recursively, so a file born
        with the wrong group is re-chowned on EVERY converge and the run never reports clean. #}
    - group: {{ common.service_group }}
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
      - file: fxc-tigase-dist-ownership
{% if 'mariadb' in roles %}
      - sls: fxc.mariadb.running
{% endif %}

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
