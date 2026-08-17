"""
Supporting execution module for the mariadb role: the MariaDB-side half of scripts/reset.sh's
default (non---hard) behavior — truncate every component's archive tables. Not meant to be called
standalone for fleet-wide resets; see fxc/orchestrate/reset_hard.sls, which calls this alongside
fxc_investor.reset_hard() on the right minions.
"""
__virtualname__ = "fxc_mariadb"

_SCHEMAS = ["fxc_pub", "fxc_broker", "fxc_exchange", "fxc_investor"]


def __virtual__():
    return __virtualname__


def truncate_archives():
    """
    Truncate every archive table (BASE TABLE, name != schema_version) across the four fxc_*
    schemas — generated from information_schema, same as scripts/reset.sh, so a table added later
    is not silently missed.
    """
    schema_list = ",".join("'{0}'".format(s) for s in _SCHEMAS)
    find_tables_sql = (
        "SELECT table_schema, table_name FROM information_schema.tables "
        "WHERE table_schema IN ({0}) AND table_type = 'BASE TABLE' "
        "AND table_name <> 'schema_version'".format(schema_list)
    )
    rows = __salt__["mysql.query"]("information_schema", find_tables_sql)
    truncated = []
    for row in rows.get("results", []):
        schema, table = row[0], row[1]
        __salt__["mysql.query"](schema, "SET FOREIGN_KEY_CHECKS=0")
        __salt__["mysql.query"](schema, "TRUNCATE TABLE `{0}`.`{1}`".format(schema, table))
        __salt__["mysql.query"](schema, "SET FOREIGN_KEY_CHECKS=1")
        truncated.append("{0}.{1}".format(schema, table))
    return {"truncated": truncated}
