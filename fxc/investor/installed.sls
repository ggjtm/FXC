{% from 'fxc/investor/map.jinja' import investor with context %}
{% from 'fxc/map.jinja' import fxc as common, jdk21_home with context %}

include:
  - fxc.common.installed
  - fxc.common.jdk21

fxc-investor-dirs:
  file.directory:
    - names:
      - {{ investor.install_dir }}
      - {{ investor.install_dir }}/conf
      - {{ investor.install_dir }}/logs
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-investor-artifact:
  archive.extracted:
    - name: {{ investor.install_dir }}
    - source: {{ investor.artifact_url }}
    - source_hash: {{ investor.artifact_sha256 }}
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-investor-dirs

fxc-investor-conf:
  file.managed:
    - name: {{ investor.install_dir }}/conf/fxcinvestor.conf
    - source: salt://fxc/investor/files/fxcinvestor.conf.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - context:
        investor: {{ investor | tojson }}
        ofx_user: {{ salt['pillar.get']('fxc:investor:ofx_user', 'investor') | tojson }}
        ofx_password: {{ salt['pillar.get']('fxc:investor:ofx_password') | tojson }}
        xmpp_password: {{ salt['pillar.get']('fxc:investor:xmpp_password') | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', common.mariadb_host) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', common.mariadb_port) }}
        mariadb_user: {{ common.mariadb_app_user | tojson }}
        mariadb_password: {{ salt['pillar.get']('fxc:mariadb:app_password') | tojson }}
    - require:
      - archive: fxc-investor-artifact

fxc-investor-unit:
  file.managed:
    - name: /etc/systemd/system/{{ investor.service_name }}.service
    - source: salt://fxc/investor/files/fxcinvestor.service.jinja
    - template: jinja
    - context:
        investor: {{ investor | tojson }}
        common: {{ common | tojson }}
        jdk21_home: {{ jdk21_home | tojson }}
    - require:
      - archive: fxc-investor-artifact

fxc-investor-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-investor-unit
