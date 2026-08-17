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

fxc-locust-artifact:
  archive.extracted:
    - name: {{ locust.install_dir }}
    - source: {{ locust.artifact_url }}
    - source_hash: {{ locust.artifact_sha256 }}
    - archive_format: tar
    - enforce_toplevel: false
    - user: {{ common.service_user }}
    - require:
      - file: fxc-locust-dirs
      - pkg: fxc-locust-pkgs

fxc-locust-venv:
  virtualenv.managed:
    - name: {{ locust.install_dir }}/.venv
    - python: /usr/bin/python3
    - user: {{ common.service_user }}
    - require:
      - archive: fxc-locust-artifact

fxc-locust-pip-install:
  pip.installed:
    - name: locust==2.32.5
    - bin_env: {{ locust.install_dir }}/.venv
    - require:
      - virtualenv: fxc-locust-venv

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
