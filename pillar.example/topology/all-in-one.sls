roles:
  - mariadb
  - tigase
  - exchange
  - pub
  - broker
  - investor
  - locust

# Everything runs on one box, so every cross-role address the fxc/*.sls files point at split-
# topology hostnames must collapse back to localhost — without these overrides an all-in-one
# minion's services crashloop on java.net.UnknownHostException: fxc-mariadb-1.internal etc.
# (fxc/docs/PROBLEMS.md P14). Merged after fxc.* by pillar top.sls ordering, so these win.
fxc:
  mariadb:
    host: localhost
  broker:
    exchange_host: localhost
    pub_host: localhost
    xmpp_host: localhost
  pub:
    xmpp_host: localhost
  investor:
    ofx_broker_url: http://localhost:8082/ofx
    broker_console_url: http://localhost:8083
    xmpp_host: localhost
  locust:
    host: http://localhost:8082
    exchange_url: http://localhost:8090
    broker_console_url: http://localhost:8083
