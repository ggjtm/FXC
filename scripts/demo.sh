#!/usr/bin/env bash
#
# FXC end-to-end demo (PLAN Phase 6).
#
# Brings up the full stack — MariaDB + Tigase + the Locust load harness (docker compose) and all three
# backend components (FxcExchange, FxcPub, FxcBroker) — seeds two investor accounts, then runs two
# autonomous FxcInvestor agents whose orders cross to produce fills. Fills are drop-copied to FxcPub
# and published to the broker's feed; both investors read that feed over XMPP and print the statuses,
# closing the loop end to end.
#
# CONTINUOUS BY DEFAULT: the agents run until you press Ctrl-C, and Locust drives additional order flow
# you can steer from a browser. Use --batch for the old bounded walkthrough that exits on its own.
#
# The rigorous, deterministic proof of the same path is the JUnit orchestrator
# `com.fxc.investor.EndToEndDemoIT` (run via `./gradlew :FxcInvestor:test`); this script is the
# human-facing walkthrough.
#
# Usage:
#   scripts/demo.sh            # continuous: runs until Ctrl-C, consoles + Locust UI live
#   scripts/demo.sh --batch    # bounded: 20 ticks per agent, then exits (smoke test)
#   scripts/demo.sh --no-load  # continuous, but without the Locust harness (Java agents only)
#   scripts/demo.sh --down     # additionally `docker compose down` on exit
#
# (`--keep` is gone: keeping the stack up is now the default, which is all it ever did.)
#
# Requirements: Docker, and a JDK 21 for the Gradle launcher (building on JDK 25 breaks the Kotlin
# DSL — see README "JDK requirements"). If `sdk` (SDKMAN) is available this script runs `sdk env`.
#
# Do not run a Gradle task that rewrites a jar while this is up — it replaces the jar underneath the
# running JVMs, which then fail to lazily load classes they had not yet touched. Build first.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
LOG_DIR="${ROOT}/build/demo-logs"
mkdir -p "${LOG_DIR}"

TEARDOWN_INFRA=false
CONTINUOUS=true
WITH_LOAD=true
for arg in "$@"; do
  case "${arg}" in
    --down) TEARDOWN_INFRA=true ;;
    --batch) CONTINUOUS=false ;;
    --no-load) WITH_LOAD=false ;;
    # Accepted for compatibility: keeping the stack up is now the default.
    --keep) printf '[demo] --keep is the default now; ignoring\n' >&2 ;;
    *) printf '[demo] unknown option: %s (expected --batch, --no-load and/or --down)\n' "${arg}" >&2; exit 2 ;;
  esac
done

# Continuous runs use ticks=0, which Main treats as an unbounded loop; --batch keeps the original
# bounded walkthrough so there is still a run that terminates on its own.
if [[ "${CONTINUOUS}" == "true" ]]; then
  AGENT_TICKS=0
else
  AGENT_TICKS=20
fi

PIDS=()

log()  { printf '\033[1;36m[demo]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[demo] %s\033[0m\n' "$*" >&2; exit 1; }

CLEANED=false

cleanup() {
  # `trap cleanup EXIT INT TERM` fires twice on a signal — once for INT/TERM, then again for the EXIT
  # that follows — so the whole teardown used to run and report itself twice. It is idempotent, but
  # printing "Shutting down components..." twice reads like something went wrong.
  [[ "${CLEANED}" == "true" ]] && return 0
  CLEANED=true
  log "Shutting down components..."
  for pid in "${PIDS[@]:-}"; do
    [[ -n "${pid}" ]] && kill "${pid}" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  # Stop generating load before the components go away, or Locust spends its last seconds recording
  # connection failures against a broker that is already gone.
  if [[ "${WITH_LOAD}" == "true" ]]; then
    docker compose stop locust >/dev/null 2>&1 || true
  fi
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
  local gradle_args=()
  # `"${arr[@]:-}"` on an EMPTY array expands to one empty-string word, not to nothing — so the
  # `${arr[@]+...}` form is required here. Passing the empty word through made Gradle fail with
  # "Cannot locate matching tasks for an empty path", killing every component at startup.
  local a
  for a in ${@+"$@"}; do [[ -n "${a}" ]] && gradle_args+=("--args=${a}"); done
  log "Starting ${task} (log: ${logfile})"
  ./gradlew "${task}" ${gradle_args[@]+"${gradle_args[@]}"} >"${logfile}" 2>&1 &
  PIDS+=("$!")
}

# --- 1. infrastructure ------------------------------------------------------

command -v docker >/dev/null 2>&1 || die "docker is required"
log "Bringing up MariaDB + Tigase (docker compose up -d)..."
# Only the infra services here; the Locust harness starts after the broker exists to talk to.
docker compose up -d mariadb tigase
wait_for_port 127.0.0.1 3306 "MariaDB" 90
wait_for_port 127.0.0.1 5222 "Tigase XMPP" 120

# --- 2. backend components (order matters: exchange, then pub, then broker) --

# Exchange first — the broker's FIX initiator connects to it.
start_component ":FxcExchange:run" "${LOG_DIR}/exchange.log"
wait_for_log "${LOG_DIR}/exchange.log" "FxcExchange started" "FxcExchange" 180
wait_for_port 127.0.0.1 9876 "FxcExchange FIX" 60
wait_for_port 127.0.0.1 8090 "FxcExchange console" 60

# Pub next — needs Tigase (already up); broker drop-copies to it.
start_component ":FxcPub:run" "${LOG_DIR}/pub.log"
wait_for_port 127.0.0.1 9878 "FxcPub FIX drop-copy" 120

# Broker last — connects to the exchange (orders) and pub (drop-copy), serves OFX.
start_component ":FxcBroker:run" "${LOG_DIR}/broker.log"
wait_for_log "${LOG_DIR}/broker.log" "FxcBroker started" "FxcBroker" 180
wait_for_port 127.0.0.1 8082 "FxcBroker OFX" 60
wait_for_port 127.0.0.1 8083 "FxcBroker console" 60

log "Full stack is up. Backend logs: ${LOG_DIR}/{exchange,pub,broker}.log"

# --- 3. investor workload ---------------------------------------------------

# Two Java agents: these are the architectural participants — Strategy SPI, XMPP feed reading, and the
# MariaDB decision log. They are not the volume; Locust is.
#
# `booker`/`bookfish` are liquidity-managed (they scale buying to cash and sell to keep a cash floor),
# which is what lets a continuous run avoid exhausting one side of the account. `rando` is naive and
# would eventually just produce rejections, so a continuous demo does not use it.
AGENT_STRATEGY="${FXC_AGENT_STRATEGY:-booker}"
if [[ "${CONTINUOUS}" == "false" ]]; then
  log "Running two FxcInvestor '${AGENT_STRATEGY}' agents, ${AGENT_TICKS} ticks each (--batch)..."
else
  log "Running two FxcInvestor '${AGENT_STRATEGY}' agents continuously (Ctrl-C to stop)..."
fi
# The agents' visible output is their own order flow. The XMPP feed leg is silent by design —
# FeedClient folds each 'FILLED: ...' status into the MarketView (last sale for every strategy, the
# traded-volume histogram for bookfish) without printing it. Don't tell the operator to watch for
# feed lines that never appear; point at what actually shows the leg working.
log "Watch for 'BUY/SELL ... -> ROUTED' lines; each agent prices off the live feed + book."
echo "-----------------------------------------------------------------------"

# Investor B in the background (different account + seed so the two agents cross).
./gradlew :FxcInvestor:run \
  -Daccount=000654321 -Dagent.seed=7 -Dagent.strategy="${AGENT_STRATEGY}" \
  -Dagent.ticks="${AGENT_TICKS}" -Dagent.intervalMs=1500 \
  >"${LOG_DIR}/investor-b.log" 2>&1 &
PIDS+=("$!")

if [[ "${CONTINUOUS}" == "false" ]]; then
  # Bounded run: A in the foreground so its decisions stream to the terminal, then exit.
  ./gradlew :FxcInvestor:run --console=plain \
    -Daccount=000123456 -Dagent.seed=42 -Dagent.strategy="${AGENT_STRATEGY}" \
    -Dagent.ticks="${AGENT_TICKS}" -Dagent.intervalMs=1500 \
    2>&1 | sed 's/^/[investor-A] /' || true

  echo "-----------------------------------------------------------------------"
  log "Agents finished. Investor B log: ${LOG_DIR}/investor-b.log"
  log "Inspect archived rows in MariaDB, e.g.:"
  log "  docker exec fxc-mariadb mariadb -ufxc -pfxc fxc_broker -e 'SELECT * FROM CLIENT_ORDER_ARCHIVE;'"
  log "Demo complete (--batch)."
  exit 0
fi

# Continuous: background A too, so the script reaches the load harness and then parks.
./gradlew :FxcInvestor:run \
  -Daccount=000123456 -Dagent.seed=42 -Dagent.strategy="${AGENT_STRATEGY}" \
  -Dagent.ticks="${AGENT_TICKS}" -Dagent.intervalMs=1500 \
  >"${LOG_DIR}/investor-a.log" 2>&1 &
PIDS+=("$!")

# --- 4. Locust load harness -------------------------------------------------

if [[ "${WITH_LOAD}" == "true" ]]; then
  log "Starting the Locust load harness (builds the image on first run)..."
  docker compose up -d locust || warn "locust failed to start; continuing without it"
  wait_for_port 127.0.0.1 8089 "Locust web UI" 120
fi

# --- 5. park ----------------------------------------------------------------

echo "-----------------------------------------------------------------------"
log "Demo is running continuously. Ctrl-C to stop everything."
log ""
log "  FxcExchange console  http://localhost:8090/   candles + volume, halt/resume, clear book"
log "  FxcBroker console    http://localhost:8083/   last-sale ticker + per-account P&L, stop/start"
if [[ "${WITH_LOAD}" == "true" ]]; then
  log "  Locust load harness  http://localhost:8089/   start/stop and re-rate the workload live"
fi
log ""
log "Agent logs: ${LOG_DIR}/investor-{a,b}.log"
log "Archived rows:"
log "  docker exec fxc-mariadb mariadb -ufxc -pfxc fxc_broker -e 'SELECT COUNT(*) FROM EXECUTION_ARCHIVE;'"

# Park until interrupted. `wait` with no argument would return as soon as *any* child exits, so the
# stack would come down when the first agent finished; sleep in a loop instead.
#
# The sleep is backgrounded and waited on, which is not a stylistic choice: bash does not run a trap
# until the current foreground command completes, so `while true; do sleep 3600; done` swallows a
# `kill -INT $$` for up to an hour and never cleans up. Interactive Ctrl-C appears to work only
# because the terminal signals the whole foreground process group, sleep included — a programmatic
# signal to the script alone does not. `wait` *is* interruptible, so the trap fires immediately.
while true; do
  sleep 3600 &
  wait $! || break
done
