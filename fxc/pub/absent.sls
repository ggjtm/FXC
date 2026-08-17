{% from 'fxc/pub/map.jinja' import pub with context %}

fxc-pub-disabled:
  service.dead:
    - name: {{ pub.service_name }}
    - enable: false

fxc-pub-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ pub.service_name }}.service
    - require:
      - service: fxc-pub-disabled

fxc-pub-dir-absent:
  file.absent:
    - name: {{ pub.install_dir }}
    - require:
      - service: fxc-pub-disabled
