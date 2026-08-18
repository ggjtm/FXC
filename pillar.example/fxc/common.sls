fxc:
  common:
    # Overrides layered onto fxc/map.jinja's os-family defaults (service_user/base_dir/etc).
    mariadb_app_user: fxc
  gridgain:
    # A signed GridGain 8 Ultimate XML license (v2.1) — NEVER commit the file itself. Either point
    # license_source at a salt://, s3://, or https:// URL (root README.md's "GridGain license"
    # section), or put the XML inline in a private pillar (e.g. gridgain:v8:license: | <xml...>)
    # and name that key here as license_pillar — which wins when both are set:
    # license_pillar: gridgain:v8:license
    license_source: CHANGEME
