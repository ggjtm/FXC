{% from 'fxc/broker/map.jinja' import broker with context %}
{% from 'fxc/map.jinja' import fxc as common, jdk21_home, gridgain_jvm_opts with context %}

include:
  - fxc.common.installed
  - fxc.common.jdk21

fxc-broker-dirs:
  file.directory:
    - names:
      - {{ broker.install_dir }}
      - {{ broker.install_dir }}/conf
      - {{ broker.install_dir }}/logs
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-broker-artifact:
  archive.extracted:
    - name: {{ broker.install_dir }}
    - source: {{ broker.artifact_url }}
    - source_hash: {{ broker.artifact_sha256 }}
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-broker-dirs

fxc-broker-conf:
  file.managed:
    - name: {{ broker.install_dir }}/conf/fxcbroker.conf
    - source: salt://fxc/broker/files/fxcbroker.conf.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - context:
        broker: {{ broker | tojson }}
        ofx_user: {{ salt['pillar.get']('fxc:broker:ofx_user', 'investor') | tojson }}
        ofx_password: {{ salt['pillar.get']('fxc:broker:ofx_password') | tojson }}
        xmpp_password: {{ salt['pillar.get']('fxc:broker:xmpp_password') | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', common.mariadb_host) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', common.mariadb_port) }}
        mariadb_user: {{ common.mariadb_app_user | tojson }}
        mariadb_password: {{ salt['pillar.get']('fxc:mariadb:app_password') | tojson }}
    - require:
      - archive: fxc-broker-artifact

{#- license_pillar (inline XML via pillar) wins over license_source (URL) — fxc/exchange/installed.sls
    has the full rationale. #}
fxc-broker-gridgain-license:
  file.managed:
    - name: {{ broker.install_dir }}/conf/gridgain-license.xml
{%- if salt['pillar.get']('fxc:gridgain:license_pillar') %}
    - contents_pillar: {{ salt['pillar.get']('fxc:gridgain:license_pillar') }}
{%- else %}
    - source: {{ salt['pillar.get']('fxc:gridgain:license_source') }}
{%- endif %}
    - user: {{ common.service_user }}
    - mode: '0600'
    - require:
      - file: fxc-broker-dirs

fxc-broker-unit:
  file.managed:
    - name: /etc/systemd/system/{{ broker.service_name }}.service
    - source: salt://fxc/broker/files/fxcbroker.service.jinja
    - template: jinja
    - context:
        broker: {{ broker | tojson }}
        common: {{ common | tojson }}
        jdk21_home: {{ jdk21_home | tojson }}
        gridgain_jvm_opts: {{ gridgain_jvm_opts | tojson }}
    - require:
      - archive: fxc-broker-artifact

fxc-broker-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-broker-unit
