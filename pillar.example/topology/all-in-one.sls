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
    # 512 investors offer ~340 orders/s; four OFX threads is a tail-latency trap.
    ofx_http_threads: 32
    exchange_host: localhost
    pub_host: localhost
    xmpp_host: localhost
  pub:
    xmpp_host: localhost
  investor:
    ofx_broker_url: http://localhost:8082/ofx
    broker_console_url: http://localhost:8083
    xmpp_host: localhost
    # Pin the resident agent to a market-maker account: it holds the issued float in every listed
    # symbol (docs/stories/006), so the agent quotes all 25 books off real inventory rather than the
    # cash-only account it would open for itself.
    account: '000000001'
  locust:
    host: http://localhost:8082
    exchange_url: http://localhost:8090
    broker_console_url: http://localhost:8083
    # The 512-investor run shape. autostart stays false: the exchange converges HALTED by design
    # (session.startClosed), so an autostarting swarm would fire thousands of orders into a closed
    # market before fxc_exchange.open. Drive it with POST /swarm; these values prefill the web form.
    users: 512
    spawn_rate: 8
    mix_rando: 1
    mix_booker: 0
    mix_bookfish: 0
    # rando ignores its portfolio entirely (strategies.NAIVE), so the 5s statement refresh is ~102
    # req/s of pure waste at this population. One refresh still happens per user at spawn.
    portfolio_refresh_ms: 3600000
    # 25 symbols x one GET per interval, serialized in one greenlet against the exchange's feed pool.
    market_feed_refresh_ms: 30000
