fxc:
  mariadb:
    # The mariadb role's resolved hostname/IP — every other component's conf template reads this.
    host: fxc-mariadb-1.internal
    port: 3306
    root_password: CHANGEME
    app_password: CHANGEME
    tigase_password: CHANGEME
