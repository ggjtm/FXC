{#- Artifact repository (fxc/docs/PROBLEMS.md P6): lighttpd + certbot on the salt-master-as-minion.
    Route53 and ACME both run through the aws CLI / certbot-dns-route53 on the instance role's
    credentials — Salt 3008 onedir carries no boto/route53 modules any more (the same Great Module
    Migration that surfaced as P10), so cmd.run + CLI is the deliberate mechanism, not a shortcut. #}
{% from 'fxc/artifact_repo/map.jinja' import artifact_repo, artifact_repo_pkgs with context %}

include:
  - fxc.common.installed

fxc-artifact-repo-pkgs:
  pkg.installed:
    - pkgs: {{ artifact_repo_pkgs | tojson }}

{#- Owned by the build user, not www-data: scripts/publish-artifacts.sh (run as that user by
    fxc.artifact_repo.publish) writes the tarballs here; lighttpd only ever reads. #}
fxc-artifact-repo-docroot:
  file.directory:
    - name: {{ artifact_repo.docroot }}
    - user: {{ artifact_repo.build_user }}
    - group: www-data
    - mode: '0755'
    - makedirs: true
    - require:
      - sls: fxc.common.installed

fxc-artifact-repo-dns-script:
  file.managed:
    - name: /usr/local/sbin/fxc-artifact-repo-dns
    - source: salt://fxc/artifact_repo/files/route53-upsert.sh.jinja
    - template: jinja
    - mode: '0755'
    - context:
        hostname: {{ artifact_repo.hostname | tojson }}
        zone_name: {{ artifact_repo.zone_name | tojson }}
        route53_zone_id: {{ (artifact_repo.route53_zone_id or '') | tojson }}

{#- UPSERT the A record before certbot runs: dns-01 itself doesn't need the record, but doing it
    here surfaces missing Route53 permissions at the cheapest possible step, and guarantees the
    name resolves by the time anything fetches from the vhost. `check` exits 0 when the record
    already points at this instance's public IP, so converged runs report no changes. #}
fxc-artifact-repo-dns:
  cmd.run:
    - name: /usr/local/sbin/fxc-artifact-repo-dns apply
    - unless: /usr/local/sbin/fxc-artifact-repo-dns check
    - require:
      - file: fxc-artifact-repo-dns-script

{#- First issuance only (unless-guarded); steady-state renewal is the Debian package's
    certbot.timer plus the deploy hook below. dns-01 via python3-certbot-dns-route53 rides the
    instance role — no credentials on disk and no dependency on public :80. #}
fxc-artifact-repo-cert:
  cmd.run:
    - name: >
        certbot certonly --non-interactive --agree-tos
        --email {{ artifact_repo.acme_email }}
        --dns-route53 --cert-name {{ artifact_repo.hostname }}
        -d {{ artifact_repo.hostname }}
    - unless: test -s /etc/letsencrypt/live/{{ artifact_repo.hostname }}/fullchain.pem
    - require:
      - pkg: fxc-artifact-repo-pkgs
      - cmd: fxc-artifact-repo-dns

{#- certbot.timer renews unattended, but lighttpd keeps serving the old cert until reloaded —
    the deploy hook closes that gap. Salt deliberately does NOT watch /etc/letsencrypt paths. #}
fxc-artifact-repo-deploy-hook:
  file.managed:
    - name: /etc/letsencrypt/renewal-hooks/deploy/fxc-lighttpd-reload
    - source: salt://fxc/artifact_repo/files/certbot-deploy-hook.sh.jinja
    - template: jinja
    - mode: '0755'
    - makedirs: true
    - require:
      - pkg: fxc-artifact-repo-pkgs

{#- The whole lighttpd.conf is managed (single deterministic file, not conf-enabled drop-ins).
    It requires the cert cmd so lighttpd is never (re)started against a missing pemfile — until
    the cert exists, the package-default config keeps lighttpd harmlessly on :80. #}
fxc-artifact-repo-conf:
  file.managed:
    - name: /etc/lighttpd/lighttpd.conf
    - source: salt://fxc/artifact_repo/files/lighttpd.conf.jinja
    - template: jinja
    - context:
        hostname: {{ artifact_repo.hostname | tojson }}
        docroot: {{ artifact_repo.docroot | tojson }}
    - require:
      - pkg: fxc-artifact-repo-pkgs
      - cmd: fxc-artifact-repo-cert
