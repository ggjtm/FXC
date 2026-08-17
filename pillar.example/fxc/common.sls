fxc:
  common:
    # Overrides layered onto fxc/map.jinja's os-family defaults (service_user/base_dir/etc).
    mariadb_app_user: fxc
  gridgain:
    # A signed GridGain 8 Ultimate XML license (v2.1) — NEVER commit the file itself. Point this at
    # a salt://, s3://, or https:// URL instead (root README.md's "GridGain license" section).
    license_source: CHANGEME
