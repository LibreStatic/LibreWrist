#!/usr/bin/env bash
# Build everything: build_all.sh
# Inputs: official APKs in $APKS_DIR (see apks/SHA256SUMS) and the dependencies from
# scripts/fetch_deps.sh. Signed outputs go to $DIST_DIR.
set -euo pipefail
source "$(dirname "$0")/lib/common.sh"

echo "== prefix=$PKG_PREFIX dist=$DIST_DIR"

echo "== phone SHM"
"$SHM_ROOT/scripts/build_shm_phone.sh"

echo "== watch SHM"
"$SHM_ROOT/scripts/build_shm_watch.sh"

echo "== bridge (phone + watch)"
"$SHM_ROOT/shm-bridge/scripts/build-bridge.sh"

echo "== Alarm app fork"
"$SHM_ROOT/scripts/build_alarm_fork.sh"

echo "== done:"
ls -1 "$DIST_DIR"
