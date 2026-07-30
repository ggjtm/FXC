#!/usr/bin/env bash
#
# Run the FXC investor load harness from a host virtualenv (FxcInvestor/docs/stories/006).
#
# The demo path is the container (`scripts/demo.sh` brings it up, or `docker compose up -d locust`).
# This script is the *iteration* path: it runs the harness straight from `loadgen/` so a change to the
# locustfile takes effect on restart with no image rebuild.
#
# It does NOT start the FXC components — point it at a stack that is already running, e.g. one started
# by `scripts/demo.sh --no-load`.
#
# Usage:
#   scripts/loadtest.sh                       # interactive: web UI on http://localhost:8089
#   scripts/loadtest.sh --headless            # no UI; runs at the default users/rate and exits on Ctrl-C
#   scripts/loadtest.sh --users 20 --spawn-rate 5
#   scripts/loadtest.sh --mix-rando 1 --mix-booker 4 --mix-bookfish 2   # shares of the user count
#   scripts/loadtest.sh --strategy bookfish   # shorthand for a single-type run
#   scripts/loadtest.sh --headless --run-time 1m --max-p95-ms 1000 --min-accepted 100  # gated run
#   scripts/loadtest.sh -- --run-time 5m      # anything after `--` goes straight to locust
#
# With no mix flags it runs one investor of each type (rando, booker, bookfish) and all three counts
# are steerable in the UI mid-run.
#
# Any flag this script does not recognise is passed through to locust, so `locust --help` is the full
# reference. Reports land in build/locust-reports/ (already gitignored via build/).
#
# Requirements: python3 (3.11+) and network access on first run to install locust into loadgen/.venv.
# The unittest suite needs neither — `cd loadgen && python3 -m unittest discover -s tests -t .`

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
LOADGEN_DIR="${ROOT}/loadgen"
VENV_DIR="${LOADGEN_DIR}/.venv"
REPORT_DIR="${ROOT}/build/locust-reports"
mkdir -p "${REPORT_DIR}"

log()  { printf '\033[1;36m[loadtest]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[loadtest]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[loadtest] %s\033[0m\n' "$*" >&2; exit 1; }

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "${pid}" ]] && kill "${pid}" 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# --- 1. prerequisites -------------------------------------------------------

command -v python3 >/dev/null 2>&1 || die "python3 is required (3.11+)"

BROKER_HOST="${FXC_BROKER_HOST:-http://localhost:8082}"
BROKER_PORT="${BROKER_HOST##*:}"
wait_for_broker() {
  local i=0
  until (exec 3<>"/dev/tcp/127.0.0.1/${BROKER_PORT}") 2>/dev/null; do
    exec 3>&- 2>/dev/null || true
    i=$((i + 1))
    [[ ${i} -ge 30 ]] && die "FxcBroker OFX is not listening on ${BROKER_HOST} — start the stack first (scripts/demo.sh --no-load)"
    sleep 1
  done
  exec 3>&- 2>/dev/null || true
}
log "Checking FxcBroker OFX at ${BROKER_HOST}..."
wait_for_broker
log "Broker is up."

# --- 2. virtualenv ----------------------------------------------------------

if [[ ! -x "${VENV_DIR}/bin/locust" ]]; then
  log "Creating ${VENV_DIR} and installing locust (first run only)..."
  python3 -m venv "${VENV_DIR}" || die "could not create the virtualenv"
  "${VENV_DIR}/bin/pip" install --quiet --upgrade pip
  # Editable install so edits to fxc_loadgen/ are picked up without reinstalling.
  "${VENV_DIR}/bin/pip" install --quiet -e "${LOADGEN_DIR}" \
    || die "could not install loadgen (needs network on first run)"
  log "Installed."
fi

# --- 3. run -----------------------------------------------------------------

# bookfish samples market-wide traded volume from the exchange's public chart feed; on this path the
# stack is local, so default to it rather than leaving bookfish to see only its own fills. Set
# FXC_EXCHANGE_URL= (empty) to run without it.
export FXC_EXCHANGE_URL="${FXC_EXCHANGE_URL-http://localhost:8090}"

log "Reports: ${REPORT_DIR}"
log "Remember: a green 'POST ofx-order' row alone does not mean orders are reaching the exchange —"
log "watch the '<STRATEGY> accepted' rows. Every OFX failure comes back as HTTP 200."
log "'BOOKFISH skipped:no-edge' climbing is patience, not a fault (stories/003)."
echo "-----------------------------------------------------------------------"

exec "${VENV_DIR}/bin/locust" \
  -f "${LOADGEN_DIR}/locustfile.py" \
  --host "${BROKER_HOST}" \
  --html "${REPORT_DIR}/index.html" \
  --csv "${REPORT_DIR}/stats" \
  "$@"
