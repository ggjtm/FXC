#!/usr/bin/env bash
# One-shot init step (docker-compose analog of a K8s initContainer): loads Tigase's DB schema into
# MariaDB, applying a minimal compatibility fix first.
#
# Tigase 8.4.1's MySQL schema declares tig_broadcast_recipients.jid_id as signed BIGINT but its
# parent tig_broadcast_jids.jid_id as BIGINT UNSIGNED. MySQL tolerates the signedness mismatch;
# MariaDB rejects it (errno 150), which aborts the whole core-schema load. We patch that single
# column to BIGINT UNSIGNED in a WRITABLE copy of the schema at runtime — the vendor distribution
# baked into the image stays pristine (the fix lives in this init step, not in the image).
set -uo pipefail
cd "${TIGASE_HOME}"

SCHEMA="database/mysql-server-8.0.0-schema.sql"

echo "[tigase-init] patching ${SCHEMA}: tig_broadcast_recipients.jid_id -> BIGINT UNSIGNED (MariaDB FK fix)"
sed -i -E 's/(jid_id[[:space:]]+bigint)([[:space:]]+not[[:space:]]+null[[:space:]]+references[[:space:]]+tig_broadcast_jids)/\1 unsigned\2/I' "${SCHEMA}"
if ! grep -qiE 'jid_id[[:space:]]+bigint[[:space:]]+unsigned[[:space:]]+not[[:space:]]+null[[:space:]]+references[[:space:]]+tig_broadcast_jids' "${SCHEMA}"; then
  echo "[tigase-init] ERROR: FK patch not applied — Tigase schema layout may have changed."
  exit 1
fi

# Provision FXC's trusted service accounts (docs/DESIGN.md §4.3) during schema load, using the
# schema tool's --adminJID/--adminJIDpass. Dev password is shared; override via TIGASE_SVC_PASS.
SVC_ACCOUNTS="admin@fxc.local,broker@fxc.local,pub-service@fxc.local,investor@fxc.local"
SVC_PASS="${TIGASE_SVC_PASS:-secret}"

echo "[tigase-init] loading/upgrading Tigase schema + provisioning service accounts into MariaDB..."
OUT="$(bash scripts/tigase.sh upgrade-schema etc/tigase.conf -J "${SVC_ACCOUNTS}" -N "${SVC_PASS}" < /dev/null 2>&1)"
echo "${OUT}" | tail -30
if echo "${OUT}" | grep -qiE 'Tigase XMPP Server \(Core\).*error'; then
  echo "[tigase-init] ERROR: core schema failed to load."
  exit 1
fi
echo "[tigase-init] schema load OK."
