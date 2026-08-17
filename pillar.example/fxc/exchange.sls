fxc:
  exchange:
    # Served by the artifact-repo role (scripts/publish-artifacts.sh via fxc.artifact_repo.publish
    # on the salt-master — fxc/docs/PROBLEMS.md P6, resolved). Stable names: the .sha256 sidecar
    # tracks rebuilds, so these URLs never change per build.
    artifact_url: https://artifacts.mariagrid.ddzone.io/exchange.tar
    artifact_sha256: https://artifacts.mariagrid.ddzone.io/exchange.tar.sha256
