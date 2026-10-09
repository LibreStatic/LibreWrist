#!/usr/bin/env bash
# Build the patched SHM watch app.
#
#   scripts/build_shm_watch.sh [gms]
#
# official APK (sha256 verified) -> watch-mod (EcgAdapter, WatchOverrides and the
# shminterop.hsdk Health Tracking client) compiled to smali -> superpatcher
# (catalogue/catalogue.json, profile gms) injecting it as a new smali_classesN dir ->
# apktool b -> sign -> $DIST_DIR/shm-watch-1.5.1.022-gms.apk
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROFILE="${1:-gms}"
[ "$PROFILE" = gms ] || die "unknown profile: $PROFILE (only gms; the PC relay variant was dropped)"
require_tools

OFFICIAL="$(official_apk shm-watch-1.5.1.022.apk)"
W="$WORK_DIR/build-shm-watch"
rm -rf "$W"; mkdir -p "$W"

MOD_SMALI="$WORK_DIR/watch-mod/smali"
"$SHM_ROOT/watch-mod/build-watch-mod.sh" "$MOD_SMALI"

python3 "$SHM_ROOT/scripts/superpatcher.py" patch "$OFFICIAL" --profile "$PROFILE" \
  -o "$W" --name shm-watch-1.5.1.022-"$PROFILE" \
  --inject watch-mod="$MOD_SMALI"

OUT="$DIST_DIR/shm-watch-1.5.1.022-$PROFILE.apk"
sign_apk "$W/shm-watch-1.5.1.022-$PROFILE-unsigned.apk" "$OUT"
rm -rf "$W"
echo "OK: $OUT"
