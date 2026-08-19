fxc:
  common:
    # Overrides layered onto fxc/map.jinja's os-family defaults (service_user/base_dir/etc).
    # QuickFIX/J logs every FIX message in and out at INFO through slf4j-simple's synchronized
    # System.err — gigabytes an hour and a real bottleneck under load, so the FIX interface is
    # gated to WARN. Set this to 'info' (or 'debug'/'trace') ONLY while actively debugging the FIX
    # wire, then put it back. quickfixj.errorEvent is never gated, so errors always surface.
    # fix_log_level: warn
    mariadb_app_user: fxc
  gridgain:
    # A signed GridGain 8 Ultimate XML license (v2.1) — NEVER commit the file itself. Either point
    # license_source at a salt://, s3://, or https:// URL (root README.md's "GridGain license"
    # section), or put the XML inline in a private pillar (e.g. gridgain:v8:license: | <xml...>)
    # and name that key here as license_pillar — which wins when both are set:
    # license_pillar: gridgain:v8:license
    license_source: CHANGEME
