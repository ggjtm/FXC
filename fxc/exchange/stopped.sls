{% from 'fxc/exchange/map.jinja' import exchange with context %}

fxc-exchange-stopped:
  service.dead:
    - name: {{ exchange.service_name }}
