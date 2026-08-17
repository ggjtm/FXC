{% from 'fxc/pub/map.jinja' import pub with context %}
{% from 'fxc/map.jinja' import fxc as common, jdk21_home, gridgain_jvm_opts with context %}

include:
  - fxc.common.installed
  - fxc.common.jdk21

fxc-pub-dirs:
  file.directory:
    - names:
      - {{ pub.install_dir }}
      - {{ pub.install_dir }}/conf
      - {{ pub.install_dir }}/logs
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-pub-artifact:
  archive.extracted:
    - name: {{ pub.install_dir }}
    - source: {{ pub.artifact_url }}
    - source_hash: {{ pub.artifact_sha256 }}
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-pub-dirs

fxc-pub-conf:
  file.managed:
    - name: {{ pub.install_dir }}/conf/fxcpub.conf
    - source: salt://fxc/pub/files/fxcpub.conf.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - context:
        pub: {{ pub | tojson }}
        xmpp_password: {{ salt['pillar.get']('fxc:pub:xmpp_password') | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', common.mariadb_host) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', common.mariadb_port) }}
        mariadb_user: {{ common.mariadb_app_user | tojson }}
        mariadb_password: {{ salt['pillar.get']('fxc:mariadb:app_password') | tojson }}
    - require:
      - archive: fxc-pub-artifact

fxc-pub-gridgain-license:
  file.managed:
    - name: {{ pub.install_dir }}/conf/gridgain-license.xml
    - source: {{ salt['pillar.get']('fxc:gridgain:license_source') }}
    - user: {{ common.service_user }}
    - mode: '0600'
    - require:
      - file: fxc-pub-dirs

fxc-pub-unit:
  file.managed:
    - name: /etc/systemd/system/{{ pub.service_name }}.service
    - source: salt://fxc/pub/files/fxcpub.service.jinja
    - template: jinja
    - context:
        pub: {{ pub | tojson }}
        common: {{ common | tojson }}
        jdk21_home: {{ jdk21_home | tojson }}
        gridgain_jvm_opts: {{ gridgain_jvm_opts | tojson }}
    - require:
      - archive: fxc-pub-artifact

fxc-pub-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-pub-unit
