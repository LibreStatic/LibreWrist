#!/usr/bin/env bash
# Build the patched Samsung Health (phone).
#
#   scripts/build_shealth.sh [shealth]
#
# official APK (sha256 verified) -> superpatcher (catalogue/catalogue.json, profile shealth) ->
# apktool b -> sign -> $DIST_DIR/shealth-7.00.6.011-shealth.apk
#
# Patterns of the profile marked "offByDefault" in the catalogue (the optional Local-only mode that
# removes the mandatory Samsung Account sign-in) are left out unless their env var is set to 1:
#   SHEALTH_OOBE_LOCAL=1 scripts/build_shealth.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROFILE="${1:-shealth}"
[ "$PROFILE" = shealth ] || die "unknown profile: $PROFILE (only shealth)"
require_tools

OFFICIAL="$(official_apk shealth-7.00.6.011.apk)"
W="$WORK_DIR/build-shealth"
rm -rf "$W"; mkdir -p "$W"

# Skip every offByDefault pattern of the profile unless the environment turns it on. One env var
# per pattern: the id upper-cased, dashes -> underscores (shealth-oobe-local -> SHEALTH_OOBE_LOCAL).
SKIP_ARGS=()
while IFS= read -r id; do
  [ -n "$id" ] || continue
  var="$(echo "$id" | tr - _ | tr '[:lower:]' '[:upper:]')"
  if [ "${!var:-0}" != 1 ]; then
    SKIP_ARGS+=(--skip "$id")
  fi
done < <(python3 - "$SHM_ROOT/catalogue/catalogue.json" "$PROFILE" <<'EOF'
import json, sys
with open(sys.argv[1]) as f:
    cat = json.load(f)
for pid in sorted({p for ids in cat['profiles'][sys.argv[2]]['families'].values() for p in ids}):
    if cat['patterns'][pid].get('offByDefault'):
        print(pid)
EOF
)

python3 "$SHM_ROOT/scripts/superpatcher.py" patch "$OFFICIAL" --profile "$PROFILE" \
  "${SKIP_ARGS[@]}" -o "$W" --name shealth-7.00.6.011-"$PROFILE"

OUT="$DIST_DIR/shealth-7.00.6.011-$PROFILE.apk"
sign_apk "$W/shealth-7.00.6.011-$PROFILE-unsigned.apk" "$OUT"
rm -rf "$W"
echo "OK: $OUT"
