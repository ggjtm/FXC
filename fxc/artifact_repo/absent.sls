{#- Removes the artifact repository's config, hook and docroot. Deliberately left behind:
    /etc/letsencrypt (re-issuing burns Let's Encrypt rate limits) and the packages (matching the
    other roles' absent.sls, which never uninstall). #}
{% from 'fxc/artifact_repo/map.jinja' import artifact_repo with context %}

fxc-artifact-repo-disabled:
  service.dead:
    - name: lighttpd
    - enable: false

fxc-artifact-repo-conf-absent:
  file.absent:
    - name: /etc/lighttpd/lighttpd.conf
    - require:
      - service: fxc-artifact-repo-disabled

fxc-artifact-repo-hook-absent:
  file.absent:
    - name: /etc/letsencrypt/renewal-hooks/deploy/fxc-lighttpd-reload
    - require:
      - service: fxc-artifact-repo-disabled

fxc-artifact-repo-dns-script-absent:
  file.absent:
    - name: /usr/local/sbin/fxc-artifact-repo-dns
    - require:
      - service: fxc-artifact-repo-disabled

fxc-artifact-repo-docroot-absent:
  file.absent:
    - name: {{ artifact_repo.docroot }}
    - require:
      - service: fxc-artifact-repo-disabled
