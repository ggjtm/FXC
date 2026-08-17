{#- Builds and stages the component artifacts into the docroot (fxc/docs/PROBLEMS.md P6 — the
    publish pipeline). Runs scripts/publish-artifacts.sh as the repo-owning build user; the
    git-rev marker makes it a no-op until HEAD moves. #}
{% from 'fxc/artifact_repo/map.jinja' import artifact_repo with context %}

include:
  - fxc.artifact_repo.installed

{#- Requires only the docroot state — NOT sls: fxc.artifact_repo.installed — so a broken
    Route53/ACME step can never block artifact staging; consumers just need TLS up before they
    fetch. timeout is generous: a cold Gradle distTar build on the master's 2 vCPU / 1.8 GB. #}
fxc-artifact-repo-publish:
  cmd.run:
    - name: ./scripts/publish-artifacts.sh --docroot {{ artifact_repo.docroot }}
    - cwd: {{ artifact_repo.repo_dir }}
    - runas: {{ artifact_repo.build_user }}
    - timeout: 1800
    - unless: >
        test -f {{ artifact_repo.docroot }}/.published-git-rev &&
        test "$(cat {{ artifact_repo.docroot }}/.published-git-rev)"
        = "$(git -C {{ artifact_repo.repo_dir }} rev-parse HEAD)"
    - require:
      - file: fxc-artifact-repo-docroot
