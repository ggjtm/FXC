{% from 'fxc/broker/map.jinja' import broker with context %}
{% from 'fxc/map.jinja' import fxc as common, jdk21_home, gridgain_jvm_opts, fix_log_opts with context %}

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

{#- The published .sha256 sidecar, kept inside the install dir. It changes exactly when a new
    build is published, which is what gates re-extraction — and because it lives INSIDE the install
    dir, wiping that dir also removes it, so the next converge repairs the install rather than
    considering it up to date. #}
fxc-broker-artifact-hash:
  file.managed:
    - name: {{ broker.install_dir }}/.artifact.sha256
    - source: {{ broker.artifact_sha256 }}
    - skip_verify: True
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - require:
      - file: fxc-broker-dirs

fxc-broker-artifact:
  archive.extracted:
    - name: {{ broker.install_dir }}
    - source: {{ broker.artifact_url }}
    - source_hash: {{ broker.artifact_sha256 }}
    {#- overwrite gated by the hash marker below. archive.extracted is otherwise satisfied the
        moment the archive's PATHS exist, so a republished tarball with identical filenames and
        different bytes silently never lands (P27); source_hash_update looks like the fix but only
        fires when salt's cached copy disagrees with its own recorded sum, which it latches on the
        first skipped run. `overwrite` is unconditional, so it is the onchanges gate that keeps this
        from re-extracting (and restarting the service) on every converge. #}
    - overwrite: True
    - onchanges:
      - file: fxc-broker-artifact-hash
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
        fix_log_opts: {{ fix_log_opts | tojson }}
    - require:
      - archive: fxc-broker-artifact

fxc-broker-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-broker-unit
