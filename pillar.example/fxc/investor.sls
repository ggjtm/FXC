fxc:
  investor:
    # Served by the artifact-repo role (scripts/publish-artifacts.sh via fxc.artifact_repo.publish
    # on the salt-master — fxc/docs/PROBLEMS.md P6, resolved). Stable names: the .sha256 sidecar
    # tracks rebuilds, so these URLs never change per build.
    artifact_url: https://artifacts.mariagrid.ddzone.io/investor.tar
    artifact_sha256: https://artifacts.mariagrid.ddzone.io/investor.tar.sha256
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
