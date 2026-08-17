{#- Convenience include of every fxc.<component> state tree. `top.sls` (roles-list driven) is the
    real entrypoint for a deployed minion; this ref exists for ad hoc `state.apply fxc` testing. #}
include:
  - fxc.common
  - fxc.mariadb
  - fxc.tigase
  - fxc.exchange
  - fxc.pub
  - fxc.broker
  - fxc.investor
  - fxc.locust
