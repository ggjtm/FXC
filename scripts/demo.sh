#!/usr/bin/env bash
#
# FXC end-to-end demo (PLAN Phase 6).
#
# Brings up the full stack — MariaDB + Tigase (docker compose) and all three backend components
# (FxcExchange, FxcPub, FxcBroker) — seeds two investor accounts, then runs two autonomous
# FxcInvestor `rando` agents whose orders cross to produce fills. Fills are drop-copied to FxcPub
# and published to the broker's feed; both investors read that feed over XMPP and print the
# statuses, closing the loop end to end.
#
# The rigorous, deterministic proof of the same path is the JUnit orchestrator
# `com.fxc.investor.EndToEndDemoIT` (run via `./gradlew :FxcInvestor:test`); this script is the
# human-facing walkthrough.
#
# Usage:
#   scripts/demo.sh            # run the demo (leaves docker infra up on exit)
#   scripts/demo.sh --down     # additionally `docker compose down` on exit
#
# Requirements: Docker, and a JDK 21 for the Gradle launcher (building on JDK 25 breaks the Kotlin
# DSL — see README "JDK requirements"). If `sdk` (SDKMAN) is available this script runs `sdk env`.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
LOG_DIR="${ROOT}/build/demo-logs"
mkdir -p "${LOG_DIR}"

TEARDOWN_INFRA=false
[[ "${1:-}" == "--down" ]] && TEARDOWN_INFRA=true

PIDS=()

log()  { printf '\033[1;36m[demo]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[demo] %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  log "Shutting down components..."
  for pid in "${PIDS[@]:-}"; do
    [[ -n "${pid}" ]] && kill "${pid}" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  if [[ "${TEARDOWN_INFRA}" == "true" ]]; then
    log "Stopping docker infrastructure (docker compose down)..."
    docker compose down || true
  else
    log "Leaving MariaDB + Tigase running. Stop them with: docker compose down"
  fi
}
trap cleanup EXIT INT TERM

# Pin the launcher JDK to 21 if SDKMAN is present (see .sdkmanrc / README).
if command -v sdk >/dev/null 2>&1 && [[ -f .sdkmanrc ]]; then
  # shellcheck disable=SC1091
  set +u; sdk env >/dev/null 2>&1 || true; set -u
fi

# --- wait helpers -----------------------------------------------------------

wait_for_port() { # host port name timeout_s
  local host="$1" port="$2" name="$3" timeout="${4:-60}" i=0
  log "Waiting for ${name} (${host}:${port})..."
  until (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
    exec 3>&- 2>/dev/null || true
    i=$((i + 1)); [[ ${i} -ge ${timeout} ]] && die "${name} did not come up on ${host}:${port} within ${timeout}s"
    sleep 1
  done
  exec 3>&- 2>/dev/null || true
  log "${name} is up."
}

wait_for_log() { # logfile pattern name timeout_s
  local file="$1" pattern="$2" name="$3" timeout="${4:-120}" i=0
  log "Waiting for ${name} to report ready..."
  until grep -q "${pattern}" "${file}" 2>/dev/null; do
    i=$((i + 1)); [[ ${i} -ge ${timeout} ]] && die "${name} did not report ready within ${timeout}s (see ${file})"
    sleep 1
  done
  log "${name} is ready."
}

start_component() { # gradle-task logfile [system-props...]
  local task="$1" logfile="$2"; shift 2
  local args=("$@") gradle_args=()
  for a in "${args[@]:-}"; do [[ -n "${a}" ]] && gradle_args+=("--args=${a}"); done
  log "Starting ${task} (log: ${logfile})"
  # shellcheck disable=SC2086
  ./gradlew "${task}" "${gradle_args[@]:-}" >"${logfile}" 2>&1 &
  PIDS+=("$!")
}

# --- 1. infrastructure ------------------------------------------------------

command -v docker >/dev/null 2>&1 || die "docker is required"
log "Bringing up MariaDB + Tigase (docker compose up -d)..."
docker compose up -d
wait_for_port 127.0.0.1 3306 "MariaDB" 90
wait_for_port 127.0.0.1 5222 "Tigase XMPP" 120

# --- 2. backend components (order matters: exchange, then pub, then broker) --

# Exchange first — the broker's FIX initiator connects to it.
start_component ":FxcExchange:run" "${LOG_DIR}/exchange.log"
wait_for_log "${LOG_DIR}/exchange.log" "FxcExchange started" "FxcExchange" 180
wait_for_port 127.0.0.1 9876 "FxcExchange FIX" 60

# Pub next — needs Tigase (already up); broker drop-copies to it.
start_component ":FxcPub:run" "${LOG_DIR}/pub.log"
wait_for_port 127.0.0.1 9878 "FxcPub FIX drop-copy" 120

# Broker last — connects to the exchange (orders) and pub (drop-copy), serves OFX.
start_component ":FxcBroker:run" "${LOG_DIR}/broker.log"
wait_for_log "${LOG_DIR}/broker.log" "FxcBroker started" "FxcBroker" 180
wait_for_port 127.0.0.1 8082 "FxcBroker OFX" 60

log "Full stack is up. Backend logs: ${LOG_DIR}/{exchange,pub,broker}.log"

# --- 3. two investor agents trade (their orders cross -> fills -> feed) ------

log "Running two FxcInvestor 'rando' agents (accounts 000123456 and 000654321)..."
log "Watch for 'FILLED ...' lines echoed from the FxcPub feed."
echo "-----------------------------------------------------------------------"

# Investor B in the background (different account + seed so the two agents cross).
./gradlew :FxcInvestor:run \
  -Daccount=000654321 -Dagent.seed=7 -Dagent.ticks=20 -Dagent.intervalMs=1500 \
  >"${LOG_DIR}/investor-b.log" 2>&1 &
PIDS+=("$!")

# Investor A in the foreground so its decisions + feed stream to the terminal.
./gradlew :FxcInvestor:run --console=plain \
  -Daccount=000123456 -Dagent.seed=42 -Dagent.ticks=20 -Dagent.intervalMs=1500 \
  2>&1 | sed 's/^/[investor-A] /' || true

echo "-----------------------------------------------------------------------"
log "Agent A finished. Investor B log: ${LOG_DIR}/investor-b.log"
log "Inspect archived rows in MariaDB, e.g.:"
log "  docker exec fxc-mariadb mariadb -ufxc -pfxc fxc_broker -e 'SELECT * FROM CLIENT_ORDER_ARCHIVE;'"
log "Demo complete."
