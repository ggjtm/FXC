{% from 'fxc/pub/map.jinja' import pub with context %}

fxc-pub-running:
  service.running:
    - name: {{ pub.service_name }}
    - enable: true
    - require:
      - sls: fxc.pub.installed
      - sls: fxc.tigase.running
      - sls: fxc.mariadb.running
    - watch:
      - file: fxc-pub-conf
      - file: fxc-pub-unit
