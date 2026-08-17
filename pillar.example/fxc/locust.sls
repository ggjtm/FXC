fxc:
  locust:
    # loadgen/ packaged as a tarball by the same (not-yet-existing) publish step as the Java
    # artifacts — docs/PLAN.md Phase 8 item 11.
    artifact_url: CHANGEME
    artifact_sha256: CHANGEME
    # Split-topology hostnames — the broker/exchange roles' resolved addresses, not localhost.
    host: http://fxc-broker-1.internal:8082
    exchange_url: http://fxc-exchange-1.internal:8090
    broker_console_url: http://fxc-broker-1.internal:8083
