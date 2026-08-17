{% from 'fxc/investor/map.jinja' import investor with context %}

fxc-investor-stopped:
  service.dead:
    - name: {{ investor.service_name }}
