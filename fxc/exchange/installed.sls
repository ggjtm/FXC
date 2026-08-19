{#- Deploys the pre-built FxcExchange distribution tarball (docs/PLAN.md Phase 8 item 11: the
    build/publish step producing exchange.artifact_url doesn't exist yet — pillar-driven so the
    URL/hash slot exists now and gets filled in once it does). #}
{% from 'fxc/exchange/map.jinja' import exchange with context %}
{% from 'fxc/map.jinja' import fxc as common, jdk21_home, gridgain_jvm_opts, fix_log_opts with context %}

include:
  - fxc.common.installed
  - fxc.common.jdk21

fxc-exchange-dirs:
  file.directory:
    - names:
      - {{ exchange.install_dir }}
      - {{ exchange.install_dir }}/conf
      - {{ exchange.install_dir }}/logs
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-exchange-artifact:
  archive.extracted:
    - name: {{ exchange.install_dir }}
    - source: {{ exchange.artifact_url }}
    - source_hash: {{ exchange.artifact_sha256 }}
    {#- Without this, archive.extracted considers itself satisfied as soon as the archive's PATHS
        exist at the destination — so a republished tarball with identical filenames and different
        bytes silently never lands, and the minion keeps running the old build forever
        (fxc/docs/PROBLEMS.md P27). #}
    - source_hash_update: True
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-exchange-dirs

fxc-exchange-conf:
  file.managed:
    - name: {{ exchange.install_dir }}/conf/fxcexchange.conf
    - source: salt://fxc/exchange/files/fxcexchange.conf.jinja
    - template: jinja
    - user: {{ common.service_user }}
    - context:
        exchange: {{ exchange | tojson }}
        mariadb_host: {{ salt['pillar.get']('fxc:mariadb:host', common.mariadb_host) | tojson }}
        mariadb_port: {{ salt['pillar.get']('fxc:mariadb:port', common.mariadb_port) }}
        mariadb_user: {{ common.mariadb_app_user | tojson }}
        mariadb_password: {{ salt['pillar.get']('fxc:mariadb:app_password') | tojson }}
    - require:
      - archive: fxc-exchange-artifact

{#- Two ways to supply the license: fxc:gridgain:license_pillar names a pillar key holding the
    XML inline (wins when set — nothing to host, the secret rides the encrypted pillar channel),
    else fxc:gridgain:license_source is a salt://, s3://, or https:// URL to the file. #}
fxc-exchange-gridgain-license:
  file.managed:
    - name: {{ exchange.install_dir }}/conf/gridgain-license.xml
{%- if salt['pillar.get']('fxc:gridgain:license_pillar') %}
    - contents_pillar: {{ salt['pillar.get']('fxc:gridgain:license_pillar') }}
{%- else %}
    - source: {{ salt['pillar.get']('fxc:gridgain:license_source') }}
{%- endif %}
    - user: {{ common.service_user }}
    - mode: '0600'
    - require:
      - file: fxc-exchange-dirs

fxc-exchange-unit:
  file.managed:
    - name: /etc/systemd/system/{{ exchange.service_name }}.service
    - source: salt://fxc/exchange/files/fxcexchange.service.jinja
    - template: jinja
    - context:
        exchange: {{ exchange | tojson }}
        common: {{ common | tojson }}
        jdk21_home: {{ jdk21_home | tojson }}
        gridgain_jvm_opts: {{ gridgain_jvm_opts | tojson }}
        fix_log_opts: {{ fix_log_opts | tojson }}
    - require:
      - archive: fxc-exchange-artifact

fxc-exchange-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-exchange-unit
