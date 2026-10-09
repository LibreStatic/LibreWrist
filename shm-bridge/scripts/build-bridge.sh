#!/usr/bin/env bash
# Builds SHM Bridge (watch + phone) without Gradle: javac + d8 + aapt2 + apksigner.
# Output: $DIST_DIR/shm-bridge-{watch,phone}.apk. versionCode/versionName come from the manifest.
# The package marker `shminterop` is rewritten to PKG_PREFIX in a staging copy.
#   BRIDGE_ONLY=watch|phone builds only one (default: both)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/../scripts/lib/common.sh"
require_tools

[ -f "$PSW_AAR" ] || die "missing $PSW_AAR (run scripts/fetch_deps.sh)"
PSW_JAR="$WORK_DIR/bridge-build/psw-classes.jar"
mkdir -p "$WORK_DIR/bridge-build"
unzip -p "$PSW_AAR" classes.jar > "$PSW_JAR"

build_one() { # $1 = dir (watch|phone) $2 = minSdk $3 = targetSdk $4 = out apk
  local dir="$1" minsdk="$2" targetsdk="$3" out="$4"
  local b="$WORK_DIR/bridge-build/$dir"
  rm -rf "$b"; mkdir -p "$b/classes" "$b/dex"
  # Sources (src, res, manifest) with the prefix applied.
  python3 "$SHM_ROOT/scripts/lib/stage_prefix.py" "$HERE/$dir" "$b/stage" >/dev/null
  local extracp=""
  if [ "$dir" = "phone" ]; then
    # Task/Tasks (play-services-tasks) are not in the AAR: compile-only stubs.
    mkdir -p "$b/stub-classes"
    javac -nowarn -d "$b/stub-classes" $(find "$HERE/phone/stubs" -name '*.java')
    extracp="$PSW_JAR:$b/stub-classes"
  fi
  javac --release 11 -nowarn -encoding UTF-8 -classpath "$PLATFORM_JAR:$extracp" -d "$b/classes" \
    $(find "$b/stage/src" -name '*.java')
  (cd "$b/classes" && jar cf "$b/classes.jar" .)
  local dexinputs=("$b/classes.jar")
  # Real Wearable API classes go into the phone dex.
  [ "$dir" = "phone" ] && dexinputs+=("$PSW_JAR")
  "$BT/d8" --release --lib "$PLATFORM_JAR" --min-api "$minsdk" --output "$b/dex" "${dexinputs[@]}"
  local resargs=()
  if [ -d "$b/stage/res" ]; then
    "$BT/aapt2" compile --dir "$b/stage/res" -o "$b/res.zip"
    resargs+=("$b/res.zip")
  fi
  "$BT/aapt2" link -o "$b/base.apk" -I "$PLATFORM_JAR" \
    --manifest "$b/stage/AndroidManifest.xml" \
    "${resargs[@]}" \
    --min-sdk-version "$minsdk" --target-sdk-version "$targetsdk"
  python3 - "$b/base.apk" "$b/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
PYEOF
  sign_apk "$b/base.apk" "$out"
  echo ">> $out"
}

[ "${BRIDGE_ONLY:-}" != phone ] && build_one watch 30 36 "$DIST_DIR/shm-bridge-watch.apk"
[ "${BRIDGE_ONLY:-}" != watch ] && build_one phone 29 35 "$DIST_DIR/shm-bridge-phone.apk"

echo "Install and grant permissions as described in shm-bridge/README.md (repeat the grants after every reinstall)."
