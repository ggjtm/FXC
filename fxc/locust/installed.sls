{#- Native venv equivalent of docker/locust/Dockerfile: fetch a pre-built loadgen/ tarball (docs/PLAN.md
    Phase 8 item 11 — same publish step as the Java tarballs), create a venv, pip-install it. #}
{% from 'fxc/locust/map.jinja' import locust, locust_pkgs with context %}
{% from 'fxc/map.jinja' import fxc as common with context %}

include:
  - fxc.common.installed

fxc-locust-pkgs:
  pkg.installed:
    - names: {{ locust_pkgs | tojson }}

fxc-locust-dirs:
  file.directory:
    - name: {{ locust.install_dir }}
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - makedirs: true
    - require:
      - sls: fxc.common.installed

{#- The published .sha256 sidecar, kept inside the install dir. It changes exactly when a new
    build is published, which is what gates re-extraction — and because it lives INSIDE the install
    dir, wiping that dir also removes it, so the next converge repairs the install rather than
    considering it up to date. #}
fxc-locust-artifact-hash:
  file.managed:
    - name: {{ locust.install_dir }}/.artifact.sha256
    - source: {{ locust.artifact_sha256 }}
    - skip_verify: True
    - user: {{ common.service_user }}
    - group: {{ common.service_group }}
    - require:
      - file: fxc-locust-dirs

fxc-locust-artifact:
  archive.extracted:
    - name: {{ locust.install_dir }}
    - source: {{ locust.artifact_url }}
    - source_hash: {{ locust.artifact_sha256 }}
    {#- overwrite gated by the hash marker below. archive.extracted is otherwise satisfied the
        moment the archive's PATHS exist, so a republished tarball with identical filenames and
        different bytes silently never lands (P27); source_hash_update looks like the fix but only
        fires when salt's cached copy disagrees with its own recorded sum, which it latches on the
        first skipped run. `overwrite` is unconditional, so it is the onchanges gate that keeps this
        from re-extracting (and restarting the service) on every converge. #}
    - overwrite: True
    - onchanges:
      - file: fxc-locust-artifact-hash
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-locust-dirs
      - pkg: fxc-locust-pkgs

{#- Not virtualenv.managed: Salt 3008's virtualenv module wants the `virtualenv` CLI (absent from
    a minimal trixie) and its stdlib-venv fallback rejects the state's `python` option outright
    with a CommandExecutionError (P19). `python3 -m venv` (python3-venv, installed above) does
    exactly what's needed — a venv on the SYSTEM python, never the onedir minion's private one. #}
fxc-locust-venv:
  cmd.run:
    - name: /usr/bin/python3 -m venv {{ locust.install_dir }}/.venv
    - runas: {{ common.service_user }}
    - creates: {{ locust.install_dir }}/.venv/bin/pip
    - require:
      - archive: fxc-locust-artifact
      - pkg: fxc-locust-pkgs

fxc-locust-pip-install:
  pip.installed:
    - name: locust==2.32.5
    - bin_env: {{ locust.install_dir }}/.venv
    - require:
      - cmd: fxc-locust-venv

fxc-locust-pip-install-loadgen:
  cmd.run:
    - name: {{ locust.install_dir }}/.venv/bin/pip install -e {{ locust.install_dir }}
    - runas: {{ common.service_user }}
    - onchanges:
      - archive: fxc-locust-artifact
    - require:
      - pip: fxc-locust-pip-install

fxc-locust-unit:
  file.managed:
    - name: /etc/systemd/system/{{ locust.service_name }}.service
    - source: salt://fxc/locust/files/fxclocust.service.jinja
    - template: jinja
    - context:
        locust: {{ locust | tojson }}
        common: {{ common | tojson }}
    - require:
      - archive: fxc-locust-artifact

fxc-locust-systemd-reload:
  module.run:
    - name: service.systemctl_reload
    - onchanges:
      - file: fxc-locust-unit
