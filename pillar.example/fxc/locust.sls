fxc:
  locust:
    # Served by the artifact-repo role (scripts/publish-artifacts.sh via fxc.artifact_repo.publish
    # on the salt-master — fxc/docs/PROBLEMS.md P6, resolved). Stable names: the .sha256 sidecar
    # tracks rebuilds, so these URLs never change per build.
    artifact_url: https://artifacts.mariagrid.ddzone.io/loadgen.tar
    artifact_sha256: https://artifacts.mariagrid.ddzone.io/loadgen.tar.sha256
    # Split-topology hostnames — the broker/exchange roles' resolved addresses, not localhost.
    host: http://fxc-broker-1.internal:8082
    exchange_url: http://fxc-exchange-1.internal:8090
    broker_console_url: http://fxc-broker-1.internal:8083
