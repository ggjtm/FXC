{% from 'fxc/investor/map.jinja' import investor with context %}

fxc-investor-disabled:
  service.dead:
    - name: {{ investor.service_name }}
    - enable: false

fxc-investor-unit-absent:
  file.absent:
    - name: /etc/systemd/system/{{ investor.service_name }}.service
    - require:
      - service: fxc-investor-disabled

fxc-investor-dir-absent:
  file.absent:
    - name: {{ investor.install_dir }}
    - require:
      - service: fxc-investor-disabled
