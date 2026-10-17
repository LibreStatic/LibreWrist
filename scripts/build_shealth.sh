#!/usr/bin/env bash
# Build the patched Samsung Health (phone).
#
#   scripts/build_shealth.sh [shealth|shealth-cn]
#
# official APK (sha256 verified) -> superpatcher (catalogue/catalogue.json, the profile) ->
# apktool b -> sign -> $DIST_DIR/shealth-<versionName>-<profile>.apk
#
# The profile picks the input: `shealth` is the global build (output shealth-phone, `from`),
# `shealth-cn` the China build (the same output's `chinaFrom`, used when the patcher app is in China
# mode). The input file name and version are read from the catalogue, not hard-coded.
#
# Patterns of the profile marked "offByDefault" in the catalogue (the optional Local-only mode that
# removes the mandatory Samsung Account sign-in) are left out unless their env var is set to 1:
#   SHEALTH_OOBE_LOCAL=1 scripts/build_shealth.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

PROFILE="${1:-shealth}"
case "$PROFILE" in
  shealth|shealth-cn) ;;
  *) die "unknown profile: $PROFILE (shealth or shealth-cn)" ;;
esac
require_tools

# The input of the profile: the file and the version of the catalogue input that the shealth-phone
# output is built from with this profile (`from` or `chinaFrom`).
read -r INPUT_FILE INPUT_VERSION < <(python3 - "$SHM_ROOT/catalogue/catalogue.json" "$PROFILE" <<'EOF'
import json, sys
with open(sys.argv[1]) as f:
    cat = json.load(f)
profile = sys.argv[2]
inputs = {i['id']: i for i in cat['inputs']}
for out in cat['outputs']:
    for key in ('from', 'chinaFrom'):
        src = out.get(key)
        if src and src.get('profile') == profile:
            i = inputs[src['input']]
            print(i['file'], i['versionName'])
            sys.exit(0)
sys.exit(f'no output is built with profile {profile}')
EOF
) || die "cannot resolve the input of profile $PROFILE from the catalogue"
[ -n "${INPUT_FILE:-}" ] || die "cannot resolve the input of profile $PROFILE from the catalogue"

OFFICIAL="$(official_apk "$INPUT_FILE")"
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
  "${SKIP_ARGS[@]}" -o "$W" --name shealth-"$INPUT_VERSION"-"$PROFILE"

OUT="$DIST_DIR/shealth-$INPUT_VERSION-$PROFILE.apk"
sign_apk "$W/shealth-$INPUT_VERSION-$PROFILE-unsigned.apk" "$OUT"
rm -rf "$W"
echo "OK: $OUT"
