{% from 'fxc/tigase/map.jinja' import tigase with context %}

fxc-tigase-disabled:
  service.dead:
    - name: {{ tigase.service_name }}
    - enable: false

fxc-tigase-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ tigase.service_name }}.service
    - require:
      - service: fxc-tigase-disabled

fxc-tigase-dir-absent:
  file.absent:
    - name: {{ tigase.install_dir }}
    - require:
      - service: fxc-tigase-disabled
