fxc:
  artifact_repo:
    # Contact email for the Let's Encrypt account (expiry notices). Required.
    acme_email: CHANGEME
    # Optional: pin the Route53 hosted-zone id; when unset the role looks it up by zone name.
    # route53_zone_id: Z0123456789ABCDEFGHIJ
    # Optional overrides (defaults shown; see fxc/artifact_repo/map.jinja for the full set —
    # hostname/zone_name too, if you deploy under a different domain):
    # docroot: /srv/fxc-artifacts
    # repo_dir: /home/admin/src/FXC.git
    # build_user: admin
    # The publish build OOM-thrashes on a swapless ~2 GB master (P17), so the role manages a
    # swapfile. Set build_swap_size to a falsy value ('') to opt out on boxes with real RAM.
    # build_swap: /swapfile
    # build_swap_size: 2G
