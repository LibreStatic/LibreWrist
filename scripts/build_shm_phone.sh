#!/usr/bin/env bash
# Build the patched SHM phone app.
#
#   scripts/build_shm_phone.sh [gms]
#
# official APK (sha256 verified) -> superpatcher (profiles/gms.json) -> apktool b -> sign
# -> $DIST_DIR/shm-phone-1.4.6.001-gms.apk
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROFILE="${1:-gms}"
[ "$PROFILE" = gms ] || die "unknown profile: $PROFILE (only gms; the PC relay variant was dropped)"
require_tools

OFFICIAL="$(official_apk shm-phone-1.4.6.001.apk)"
W="$WORK_DIR/build-shm-phone"
rm -rf "$W"; mkdir -p "$W"

python3 "$SHM_ROOT/scripts/superpatcher.py" patch "$OFFICIAL" --profile "$PROFILE" \
  -o "$W" --name shm-phone-1.4.6.001-"$PROFILE"

OUT="$DIST_DIR/shm-phone-1.4.6.001-$PROFILE.apk"
sign_apk "$W/shm-phone-1.4.6.001-$PROFILE-unsigned.apk" "$OUT"
rm -rf "$W"
echo "OK: $OUT"
