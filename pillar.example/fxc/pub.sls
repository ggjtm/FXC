fxc:
  pub:
    # Served by the artifact-repo role (scripts/publish-artifacts.sh via fxc.artifact_repo.publish
    # on the salt-master — fxc/docs/PROBLEMS.md P6, resolved). Stable names: the .sha256 sidecar
    # tracks rebuilds, so these URLs never change per build.
    artifact_url: https://artifacts.mariagrid.ddzone.io/pub.tar
    artifact_sha256: https://artifacts.mariagrid.ddzone.io/pub.tar.sha256
    xmpp_password: CHANGEME
    # Split-topology hostname — the tigase role's resolved address, not localhost.
    xmpp_host: fxc-tigase-1.internal
