{#- No cross-role P13 conditional block here — deliberately: artifact-repo depends on no other
    fxc role. Other roles depend on THIS one only cross-minion (their artifact_url fetches), which
    is pillar/orchestrate territory (P2/P13), never an sls requisite. #}
{% from 'fxc/artifact_repo/map.jinja' import artifact_repo with context %}

fxc-artifact-repo-running:
  service.running:
    - name: lighttpd
    - enable: true
    - require:
      - sls: fxc.artifact_repo.installed
    {#- Watch the conf for restarts, and the (unless-guarded) first cert issuance so the TLS
        vhost activates the moment the cert lands. Renewals reload via the certbot deploy hook —
        Salt never watches /etc/letsencrypt paths it doesn't manage. #}
    - watch:
      - file: fxc-artifact-repo-conf
      - cmd: fxc-artifact-repo-cert

{#- "started" is not "serving TLS" (P3 pattern): prove the HTTPS listener answers before
    declaring converged. -k because the SNI name is the public FQDN, not 127.0.0.1. #}
fxc-artifact-repo-ready:
  cmd.run:
    - name: curl -fsk https://127.0.0.1/ -o /dev/null
    - require:
      - service: fxc-artifact-repo-running
    - retry:
        attempts: 10
        interval: 3
