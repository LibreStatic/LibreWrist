#!/usr/bin/env bash
# Compile watch-mod (EcgAdapter, WatchOverrides and the shminterop.hsdk client) to smali ready to inject.
#
#   watch-mod/build-watch-mod.sh [OUT_SMALI_DIR]     (default: $WORK_DIR/watch-mod/smali)
#
# Steps: stage_prefix.py (marker `shminterop` -> PKG_PREFIX) -> javac against android.jar
# plus the hand-written stubs in watch-mod/stubs (signatures only, NOT in the dex) -> d8
# -> dex in a zip -> apktool d -> smali. No runtime SDK classes are needed: the Health
# Tracking client (shminterop.hsdk) is our own code, built with the rest.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib/common.sh"
require_tools

OUT="${1:-$WORK_DIR/watch-mod/smali}"
T="$WORK_DIR/watch-mod/build"
rm -rf "$T"; mkdir -p "$T/classes" "$T/stub-classes" "$T/dex"

python3 "$SHM_ROOT/scripts/lib/stage_prefix.py" "$SHM_ROOT/watch-mod/src" "$T/src" >/dev/null

javac -nowarn --release 8 -classpath "$PLATFORM_JAR" -d "$T/stub-classes" \
  $(find "$SHM_ROOT/watch-mod/stubs" -name '*.java')

javac -nowarn --release 8 -classpath "$PLATFORM_JAR:$T/stub-classes" \
  -d "$T/classes" $(find "$T/src" -name '*.java')
[ -n "$(find "$T/classes" -name '*.class' | head -1)" ] || die "javac produced no classes"

# only our classes go to the dex (the stubs stay out)
(cd "$T/classes" && jar cf "$T/classes.jar" "${PKG_PATH%%/*}")
"$BT/d8" --release --min-api 26 --lib "$PLATFORM_JAR" --classpath "$T/stub-classes" --output "$T/dex" "$T/classes.jar"

(cd "$T/dex" && zip -q "$T/dex.zip" classes.dex)
apktool d -f -r "$T/dex.zip" -o "$T/smali-out" >/dev/null
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r "$T/smali-out/smali/." "$OUT/"
rm -rf "$T"
echo "OK: watch-mod smali in $OUT"
