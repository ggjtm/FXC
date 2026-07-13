#!/usr/bin/env bash
# Runs the UNMODIFIED Tigase server in the foreground. Schema loading is handled by the separate
# one-shot init step (init-schema.sh / the tigase-init compose service) which must complete first,
# so by the time this runs the schema is already present in MariaDB.
set -uo pipefail
cd "${TIGASE_HOME}"
echo "[fxc-tigase] starting Tigase (foreground)..."
exec bash scripts/tigase.sh run
