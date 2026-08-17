fxc:
  broker:
    # Served by the artifact-repo role (scripts/publish-artifacts.sh via fxc.artifact_repo.publish
    # on the salt-master — fxc/docs/PROBLEMS.md P6, resolved). Stable names: the .sha256 sidecar
    # tracks rebuilds, so these URLs never change per build.
    artifact_url: https://artifacts.mariagrid.ddzone.io/broker.tar
    artifact_sha256: https://artifacts.mariagrid.ddzone.io/broker.tar.sha256
    ofx_user: investor
    ofx_password: CHANGEME
    xmpp_password: CHANGEME
    # Split-topology hostnames — the exchange/pub/tigase roles' resolved addresses, not localhost.
    exchange_host: fxc-exchange-1.internal
    pub_host: fxc-pub-1.internal
    xmpp_host: fxc-tigase-1.internal
