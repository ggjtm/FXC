fxc:
  investor:
    artifact_url: CHANGEME
    artifact_sha256: CHANGEME
    ofx_user: investor
    ofx_password: CHANGEME
    xmpp_password: CHANGEME
    # Split-topology hostnames — the broker/tigase roles' resolved addresses, not localhost.
    ofx_broker_url: http://fxc-broker-1.internal:8082/ofx
    broker_console_url: http://fxc-broker-1.internal:8083
    xmpp_host: fxc-tigase-1.internal
    agent_client_id: investor-1
    agent_strategy: booker
    agent_seed: 7
