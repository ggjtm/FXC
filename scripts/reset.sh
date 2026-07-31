#!/usr/bin/env bash
#
# Wipe the data a demo accumulates, so the next `scripts/demo.sh` starts from a clean market.
#
# A continuous demo is a firehose: a few minutes of it archives hundreds of thousands of rows to
# MariaDB (orders, trades, executions, decisions), and the charts, the P&L curves and `bookfish`'s
# volume-by-price all read that history back. Eventually the console shows a day of someone else's
# market. This resets it.
#
# What accumulates, and what this removes:
#
#   MariaDB (cold archives)  every component drains terminal rows here on an interval — ORDERS_ARCHIVE,
#                            TRADE_ARCHIVE, SETTLEMENT_OBLIGATION_ARCHIVE, CLIENT_ORDER_ARCHIVE,
#                            EXECUTION_ARCHIVE, STATUS_ARCHIVE, DECISION_LOG. Truncated by default;
#                            with --hard the volume itself is destroyed.
#   GridGain (hot state)     in-memory only (`gridgain.persistence.enabled = false`), so the tables
#                            themselves die with the JVMs. What survives on disk is each node's *work
#                            directory* — cluster metadata under $TMPDIR/fxc-{exchange,broker,pub}-ignite.
#                            Always removed: stale metadata from an older run is exactly the kind of
#                            thing that produces an inexplicable startup failure.
#   Logs / reports           build/demo-logs/ and build/locust-reports/. Removed unless --keep-logs.
#
# Deliberately NOT touched: the seeded accounts and their balances (FxcBroker re-seeds them at every
# start, from `account.*` in FxcBroker/conf), the `schema_version` bookkeeping (rewritten idempotently
# by each component's schema.sql), and the GridGain license file.
#
# Usage:
#   scripts/reset.sh                # truncate the archives, drop grid work dirs and demo logs
#   scripts/reset.sh --hard         # ALSO destroy the MariaDB volume — Tigase's accounts go with it
#   scripts/reset.sh --keep-logs    # leave build/demo-logs and build/locust-reports alone
#   scripts/reset.sh --dry-run      # print what would be removed, touch nothing
#   scripts/reset.sh --yes          # skip the confirmation prompt (required when not on a terminal)
#
# --hard vs the default: the default keeps the MariaDB container, its volume and Tigase's user
# accounts, and takes a second or two. --hard runs `docker compose down -v`, which removes the volume
# and therefore `tigasedb` as well; the next `docker compose up` re-runs `tigase-init` to recreate the
# schema and re-provision the four XMPP accounts, which takes a minute or so.
#
# This refuses to run while the demo is up. Truncating archives underneath a live ArchiveService, or
# deleting a work directory underneath a live GridGain node, produces failures that look like bugs in
# the components. Stop the demo first (Ctrl-C).

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

HARD=false
KEEP_LOGS=false
DRY_RUN=false
ASSUME_YES=false
for arg in "$@"; do
  case "${arg}" in
    --hard) HARD=true ;;
    --keep-logs) KEEP_LOGS=true ;;
    --dry-run) DRY_RUN=true ;;
    --yes|-y) ASSUME_YES=true ;;
    -h|--help) sed -n '2,44p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) printf '[reset] unknown option: %s (expected --hard, --keep-logs, --dry-run and/or --yes)\n' "${arg}" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[1;36m[reset]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[reset]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[reset] %s\033[0m\n' "$*" >&2; exit 1; }

run() {
  # Every mutation goes through here, so --dry-run cannot miss one.
  if [[ "${DRY_RUN}" == "true" ]]; then
    printf '\033[1;35m[reset:dry-run]\033[0m %s\n' "$*"
  else
    "$@"
  fi
}

COMPOSE=(docker compose)
MARIADB_CONTAINER="${FXC_MARIADB_CONTAINER:-fxc-mariadb}"
MARIADB_USER="${FXC_MARIADB_USER:-fxc}"
MARIADB_PASSWORD="${FXC_MARIADB_PASSWORD:-fxc}"
SCHEMAS=(fxc_exchange fxc_broker fxc_pub fxc_investor)

# --- 1. refuse to run against a live demo ------------------------------------

running_components() {
  # The component JVMs, not the Gradle launcher: `-Dorg.gradle.` would also match a build.
  pgrep -f 'com\.fxc\.(exchange|broker|pub|investor)\.Main' 2>/dev/null || true
}

busy_ports() {
  # 8082 broker OFX, 8083 broker console, 8090 exchange feed, 9876/9878 FIX acceptors.
  #
  # `-sTCP:LISTEN` is load-bearing: without it lsof also reports processes holding *client* sockets on
  # those ports, which on this stack means Docker's backend proxying the Locust container's connection
  # to the host broker — and a lingering TIME_WAIT would refuse a reset with nothing running at all.
  # Found by hitting it.
  lsof -ti:8082,8083,8090,9876,9878 -sTCP:LISTEN 2>/dev/null || true
}

if [[ -n "$(running_components)" || -n "$(busy_ports)" ]]; then
  die "the demo appears to be running (component JVMs or their ports are live).
       Stop it first — Ctrl-C in the demo terminal — then run this again.
       Truncating archives under a live ArchiveService, or deleting a work directory under a live
       GridGain node, fails in ways that look like component bugs."
fi

# --- 2. work out what there is to remove -------------------------------------

mariadb_up() {
  docker ps --filter "name=^/${MARIADB_CONTAINER}$" --filter "status=running" --format '{{.Names}}' \
    2>/dev/null | grep -q .
}

mariadb_query() {
  docker exec "${MARIADB_CONTAINER}" mariadb -u"${MARIADB_USER}" -p"${MARIADB_PASSWORD}" -N -B -e "$1" \
    2>/dev/null
}

# `'fxc_exchange','fxc_broker',...` for an IN clause. Spelled out rather than joined via IFS, because
# IFS joins on its *first* character only: `IFS="','"` yields `a'b'c`, which silently matches no rows
# and makes a full database look empty.
schema_list() {
  local out="" schema
  for schema in "${SCHEMAS[@]}"; do
    out+="${out:+,}'${schema}'"
  done
  printf '%s' "${out}"
}
SCHEMA_LIST="$(schema_list)"

# `schema_version` is bookkeeping the components rewrite on every start; emptying it would only make a
# reset look like a fresh install. Everything else in these schemas is archived demo data.
tables_sql="SELECT table_schema, table_name FROM information_schema.tables
            WHERE table_schema IN (${SCHEMA_LIST})
              AND table_type = 'BASE TABLE' AND table_name <> 'schema_version'
            ORDER BY table_schema, table_name"

ARCHIVE_ROWS=""
if mariadb_up; then
  ARCHIVE_ROWS="$(mariadb_query "
    SELECT CONCAT(table_schema, '.', table_name, ' ', table_rows)
    FROM information_schema.tables
    WHERE table_schema IN (${SCHEMA_LIST})
      AND table_type = 'BASE TABLE' AND table_name <> 'schema_version'
      AND table_rows > 0
    ORDER BY table_rows DESC" || true)"
fi

# Each component's GridGain work directory: `gridgain.workDir` in its conf if set, else the default
# Main.java computes ($TMPDIR/fxc-<component>-ignite). Read rather than assumed, so a conf override is
# honoured instead of silently skipped.
work_dir_for() {
  local conf="$1" default="$2" configured
  configured="$(sed -n 's/^[[:space:]]*gridgain\.workDir[[:space:]]*=[[:space:]]*//p' "${conf}" 2>/dev/null | tail -1)"
  configured="${configured%%#*}"                       # a trailing `#` is part of the value to java.util.Properties
  configured="$(printf '%s' "${configured}" | xargs 2>/dev/null || true)"
  printf '%s' "${configured:-${default}}"
}

TMP_ROOT="${TMPDIR:-/tmp}"
GRID_DIRS=(
  "$(work_dir_for "${ROOT}/FxcExchange/conf/fxcexchange.conf" "${TMP_ROOT%/}/fxc-exchange-ignite")"
  "$(work_dir_for "${ROOT}/FxcBroker/conf/fxcbroker.conf"     "${TMP_ROOT%/}/fxc-broker-ignite")"
  "$(work_dir_for "${ROOT}/FxcPub/conf/fxcpub.conf"           "${TMP_ROOT%/}/fxc-pub-ignite")"
)

LOG_DIRS=("${ROOT}/build/demo-logs" "${ROOT}/build/locust-reports")

# --- 3. say exactly what is about to happen ----------------------------------

log "About to reset FXC demo data:"
if [[ "${HARD}" == "true" ]]; then
  printf '  MariaDB   \033[1;31mdestroy the volume\033[0m (docker compose down -v) — including tigasedb,\n'
  printf '            so Tigase re-provisions its accounts on the next `docker compose up`\n'
else
  if mariadb_up; then
    if [[ -n "${ARCHIVE_ROWS}" ]]; then
      printf '  MariaDB   truncate archive tables in %s (row counts are InnoDB estimates):\n' "${SCHEMAS[*]}"
      printf '%s\n' "${ARCHIVE_ROWS}" | awk '{ printf "              %-46s ~%s rows\n", $1, $2 }'
    else
      printf '  MariaDB   truncate archive tables in %s (already empty)\n' "${SCHEMAS[*]}"
    fi
  else
    printf '  MariaDB   \033[1;33mcontainer %s is not running — nothing to truncate\033[0m\n' "${MARIADB_CONTAINER}"
    printf '            start it (docker compose up -d mariadb) or use --hard to drop the volume\n'
  fi
fi
for dir in "${GRID_DIRS[@]}"; do
  if [[ -d "${dir}" ]]; then
    printf '  GridGain  remove %s (%s)\n' "${dir}" "$(du -sh "${dir}" 2>/dev/null | cut -f1)"
  else
    printf '  GridGain  %s (absent)\n' "${dir}"
  fi
done
if [[ "${KEEP_LOGS}" == "true" ]]; then
  printf '  Logs      kept (--keep-logs)\n'
else
  for dir in "${LOG_DIRS[@]}"; do
    [[ -d "${dir}" ]] && printf '  Logs      remove %s\n' "${dir}"
  done
fi

if [[ "${DRY_RUN}" != "true" && "${ASSUME_YES}" != "true" ]]; then
  [[ -t 0 ]] || die "not a terminal: pass --yes to confirm, or --dry-run to see what would happen"
  read -r -p "$(printf '\033[1;33m[reset]\033[0m Proceed? [y/N] ')" answer
  [[ "${answer}" =~ ^[Yy]$ ]] || die "aborted; nothing was changed"
fi

# --- 4. MariaDB ---------------------------------------------------------------

if [[ "${HARD}" == "true" ]]; then
  log "Removing containers and volumes (docker compose down -v)..."
  run "${COMPOSE[@]}" down -v
  log "Volumes gone. The next 'docker compose up' re-creates the schemas and re-runs tigase-init."
elif mariadb_up; then
  log "Truncating archive tables..."
  # Generated from information_schema so a table added later is not silently missed. FK checks off
  # because TRUNCATE refuses on a referenced table, and the archives are drained copies anyway.
  truncate_sql="$(mariadb_query "SELECT CONCAT('TRUNCATE TABLE \`', table_schema, '\`.\`', table_name, '\`;')
                                 FROM (${tables_sql}) t" || true)"
  if [[ -z "${truncate_sql}" ]]; then
    warn "no archive tables found — has the stack ever run against this MariaDB?"
  else
    run docker exec -i "${MARIADB_CONTAINER}" mariadb -u"${MARIADB_USER}" -p"${MARIADB_PASSWORD}" \
      -e "SET FOREIGN_KEY_CHECKS=0; ${truncate_sql} SET FOREIGN_KEY_CHECKS=1;"
    log "Archives emptied."
  fi
else
  warn "MariaDB container ${MARIADB_CONTAINER} is not running — skipped the archives."
fi

# --- 5. GridGain work directories --------------------------------------------

for dir in "${GRID_DIRS[@]}"; do
  # Only ever remove a directory that looks like one of ours: a bad `gridgain.workDir` should not turn
  # a reset into an rm -rf of something else.
  case "$(basename "${dir}")" in
    *ignite*|*gridgain*|*grid*) ;;
    *) warn "refusing to remove ${dir}: it does not look like a GridGain work directory"; continue ;;
  esac
  [[ -d "${dir}" ]] || continue
  log "Removing GridGain work directory ${dir}"
  run rm -rf "${dir}"
done

# --- 6. logs and reports ------------------------------------------------------

if [[ "${KEEP_LOGS}" != "true" ]]; then
  for dir in "${LOG_DIRS[@]}"; do
    [[ -d "${dir}" ]] || continue
    log "Removing ${dir}"
    run rm -rf "${dir}"
  done
fi

# --- 7. show the evidence -----------------------------------------------------

if [[ "${DRY_RUN}" == "true" ]]; then
  log "Dry run: nothing was changed."
  exit 0
fi

if [[ "${HARD}" != "true" ]] && mariadb_up; then
  # COUNT(*), not information_schema.table_rows: that column is an InnoDB *estimate* — good enough to
  # show the operator what is about to go, useless as proof that it went.
  counts_sql="$(mariadb_query "
    SELECT GROUP_CONCAT(CONCAT('SELECT COUNT(*) c FROM \`', table_schema, '\`.\`', table_name, '\`')
                        SEPARATOR ' UNION ALL ')
    FROM (${tables_sql}) t" || true)"
  # GROUP_CONCAT over no rows is NULL, which `-N` prints as the literal "NULL" — feeding that back in
  # would be a syntax error reported as an unexplained "?".
  if [[ -n "${counts_sql}" && "${counts_sql}" != "NULL" ]]; then
    remaining="$(mariadb_query "SELECT COALESCE(SUM(c), 0) FROM (${counts_sql}) x" || echo '?')"
    log "Archive rows remaining (counted, not estimated): ${remaining:-0}"
  fi
fi

log "Done. Next run starts from an empty market:"
if [[ "${HARD}" == "true" ]]; then
  log "  scripts/demo.sh        # brings the containers back up; tigase-init re-provisions accounts"
else
  log "  scripts/demo.sh"
fi
