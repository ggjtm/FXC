{% from 'fxc/investor/map.jinja' import investor with context %}

fxc-investor-running:
  service.running:
    - name: {{ investor.service_name }}
    - enable: true
    - require:
      - sls: fxc.investor.installed
      - sls: fxc.broker.running
    - watch:
      - file: fxc-investor-conf
      - file: fxc-investor-unit
