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

{#- The published .sha256 sidecar, kept inside the install dir. It changes exactly when a new
    build is published, which is what gates re-extraction — and because it lives INSIDE the install
    dir, wiping that dir also removes it, so the next converge repairs the install rather than
    considering it up to date. #}
fxc-investor-artifact-hash:
  file.managed:
    - name: {{ investor.install_dir }}/.artifact.sha256
    - source: {{ investor.artifact_sha256 }}
    - skip_verify: True
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - require:
      - file: fxc-investor-dirs

fxc-investor-artifact:
  archive.extracted:
    - name: {{ investor.install_dir }}
    - source: {{ investor.artifact_url }}
    - source_hash: {{ investor.artifact_sha256 }}
    {#- overwrite gated by the hash marker below. archive.extracted is otherwise satisfied the
        moment the archive's PATHS exist, so a republished tarball with identical filenames and
        different bytes silently never lands (P27); source_hash_update looks like the fix but only
        fires when salt's cached copy disagrees with its own recorded sum, which it latches on the
        first skipped run. `overwrite` is unconditional, so it is the onchanges gate that keeps this
        from re-extracting (and restarting the service) on every converge. #}
    - overwrite: True
    - onchanges:
      - file: fxc-investor-artifact-hash
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
