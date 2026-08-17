#!/usr/bin/env bash
#
# Build and stage the FXC artifact tarballs into the artifact repository docroot — the publish
# pipeline fxc/docs/PROBLEMS.md P6 said didn't exist (docs/PLAN.md Phase 8 item 9, fxc/docs/PLAN.md
# item 11). Normally invoked by the fxc.artifact_repo.publish Salt state on the salt-master, which
# guards re-runs with the .published-git-rev marker; safe to run by hand too.
#
# What it publishes, and why the shapes are what they are:
#
#   exchange.tar, pub.tar,     `./gradlew :<Module>:distTar` output, REPACKED FLAT: Gradle's
#   broker.tar, investor.tar   application plugin nests everything under <Module>-<version>/, but
#                              the fxc/<role>/installed.sls archive.extracted states use
#                              `enforce_toplevel: false` with no --strip-components and expect
#                              bin/ + lib/ at the archive top (contrast P11, where Tigase's vendor
#                              tarball got --strip-components=1 instead).
#   loadgen.tar                the CONTENTS of loadgen/ (pyproject.toml, locustfile.py,
#                              fxc_loadgen/) at the archive top — fxc/locust/installed.sls unpacks
#                              it and runs `pip install -e` against the unpack dir, so pyproject
#                              must not be nested. loadgen/ stays outside the Gradle build by
#                              design (see loadgen/pyproject.toml's header).
#   <name>.tar.sha256          `sha256sum` sidecars ("<hex>  <name>.tar"); the pillar
#                              artifact_sha256 keys point at these URLs, so the pillar never
#                              changes per build — archive.extracted re-extracts on hash change.
#   .published-git-rev         the repo HEAD this publish came from, written LAST so an
#                              interrupted publish re-runs in full.
#
# Usage:
#   scripts/publish-artifacts.sh                         # build + stage into /srv/fxc-artifacts
#   scripts/publish-artifacts.sh --docroot PATH          # stage somewhere else
#   scripts/publish-artifacts.sh --skip-build            # repack whatever build/distributions has
#   scripts/publish-artifacts.sh --dry-run               # print what would happen, touch nothing
set -euo pipefail
cd "$(dirname "$0")/.."

DOCROOT=/srv/fxc-artifacts
SKIP_BUILD=0
DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --docroot) DOCROOT="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
done

# name -> Gradle module, matching the fxc:<name> pillar keys
COMPONENTS="exchange=FxcExchange pub=FxcPub broker=FxcBroker investor=FxcInvestor"

if [ "$DRY_RUN" = 1 ]; then
  echo "[publish] dry run: would build distTar for ${COMPONENTS}, repack flat + loadgen.tar"
  echo "[publish] dry run: would stage *.tar + *.tar.sha256 + .published-git-rev into ${DOCROOT}"
  exit 0
fi

if [ "$SKIP_BUILD" = 0 ]; then
  # --no-daemon: the salt-master is a 2 vCPU / 1.8 GB box; a resident Gradle daemon JVM is the
  # difference between a slow build and an OOM-killed one.
  echo "[publish] building distributions (gradle --no-daemon)..."
  ./gradlew --no-daemon -q :FxcExchange:distTar :FxcPub:distTar :FxcBroker:distTar :FxcInvestor:distTar
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

for pair in ${COMPONENTS}; do
  name="${pair%%=*}"
  module="${pair##*=}"
  dist="$(ls "${module}"/build/distributions/"${module}"-*.tar 2>/dev/null | head -1)"
  if [ -z "${dist}" ]; then
    echo "[publish] ERROR: no distTar output for ${module} (ran with --skip-build?)" >&2
    exit 1
  fi
  tmp="$(mktemp -d)"
  tar -xf "${dist}" -C "${tmp}"
  inner="$(ls "${tmp}")"
  if [ "$(echo "${inner}" | wc -l)" != 1 ]; then
    echo "[publish] ERROR: expected a single top-level dir in ${dist}, got: ${inner}" >&2
    exit 1
  fi
  tar -cf "${STAGE}/${name}.tar" -C "${tmp}/${inner}" .
  rm -rf "${tmp}"
  echo "[publish] repacked ${dist} -> ${name}.tar (flat)"
done

tar -cf "${STAGE}/loadgen.tar" -C loadgen pyproject.toml locustfile.py fxc_loadgen
echo "[publish] packed loadgen/ -> loadgen.tar"

(cd "${STAGE}" && for t in *.tar; do sha256sum "${t}" > "${t}.sha256"; done)

# Per-file atomic-ish: rename tar then its sidecar, so a reader never sees a sidecar whose hash
# doesn't match some version of the tar for longer than the gap between two renames.
for f in "${STAGE}"/*.tar; do
  base="$(basename "${f}")"
  mv "${f}" "${DOCROOT}/${base}"
  mv "${f}.sha256" "${DOCROOT}/${base}.sha256"
  echo "[publish] staged ${DOCROOT}/${base}"
done

git rev-parse HEAD > "${DOCROOT}/.published-git-rev"
echo "[publish] done: $(git rev-parse --short HEAD) published to ${DOCROOT}"
