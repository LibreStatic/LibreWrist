# Shared helpers for the build scripts. Usage: source "$(dirname "$0")/lib/common.sh"
# (from scripts/) or with the matching relative path. Defines SHM_ROOT and loads config.env.
set -euo pipefail

SHM_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export SHM_ROOT
# shellcheck source=../../config.env
source "$SHM_ROOT/config.env"
export PKG_PREFIX ANDROID_HOME BUILD_TOOLS_VERSION PLATFORM_API APKTOOL_JAR APKTOOL_HEAP \
       PSW_AAR APKS_DIR KEYSTORE KEY_ALIAS KS_PASS WORK_DIR DIST_DIR

BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS_VERSION"
PLATFORM_JAR="$ANDROID_HOME/platforms/android-$PLATFORM_API/android.jar"
# Prefix as a path (com.example -> com/example), for smali and directories.
PKG_PATH="${PKG_PREFIX//.//}"

die() { echo "ERROR: $*" >&2; exit 1; }

require_tools() {
  [ -f "$PLATFORM_JAR" ] || die "missing $PLATFORM_JAR (PLATFORM_API=$PLATFORM_API)"
  for t in d8 aapt2 zipalign apksigner; do
    [ -x "$BT/$t" ] || die "missing $BT/$t (BUILD_TOOLS_VERSION=$BUILD_TOOLS_VERSION)"
  done
  command -v java >/dev/null || die "java not found"
  command -v javac >/dev/null || die "javac not found"
}

# apktool serialized with a global lock: decoding/rebuilding large APKs takes several GB
# of RAM, so never run two at once.
APKTOOL_LOCK="$WORK_DIR/.apktool.lock"
apktool() {
  [ -f "$APKTOOL_JAR" ] || die "missing $APKTOOL_JAR (run scripts/fetch_deps.sh)"
  flock "$APKTOOL_LOCK" java "$APKTOOL_HEAP" -jar "$APKTOOL_JAR" "$@"
}

# Generate the local keystore if it does not exist (own key; never versioned).
ensure_keystore() {
  [ -f "$KEYSTORE" ] && return 0
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=shm-interop local" >/dev/null 2>&1
  echo ">> new keystore: $KEYSTORE (alias $KEY_ALIAS)"
}

# sign_apk <unsigned.apk> <out.apk>: zipalign + apksigner with the local key.
sign_apk() {
  local in="$1" out="$2" aligned
  ensure_keystore
  aligned="$(mktemp -p "$WORK_DIR" aligned-XXXXXX.apk)"
  "$BT/zipalign" -f 4 "$in" "$aligned"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$out" "$aligned"
  rm -f "$aligned"
}

# official_apk <name in apks/SHA256SUMS>: print the path after verifying the hash.
official_apk() {
  local name="$1" want path got
  want="$(awk -v n="$name" '$2==n {print $1}' "$SHM_ROOT/apks/SHA256SUMS")"
  [ -n "$want" ] || die "$name is not listed in apks/SHA256SUMS"
  path="$APKS_DIR/$name"
  [ -f "$path" ] || die "missing $path (see apks/README.md)"
  got="$(sha256sum "$path" | cut -d' ' -f1)"
  [ "$got" = "$want" ] || die "$name: sha256 $got does not match the expected $want"
  echo "$path"
}

# java_to_smali <src_dir> <out_smali_dir> <java_release> <min_api>: stage the package placeholder
# (stage_prefix.py), javac against android.jar, d8, then apktool d (dex -> smali). Only the
# classes of <src_dir> end up in the dex. Needs require_tools.
java_to_smali() {
  local src="$1" out="$2" release="$3" min_api="$4" t
  t="$(mktemp -d -p "$WORK_DIR" java2smali-XXXXXX)"
  mkdir -p "$t/classes" "$t/dex"
  python3 "$SHM_ROOT/scripts/lib/stage_prefix.py" "$src" "$t/src" >/dev/null
  javac -nowarn --release "$release" -classpath "$PLATFORM_JAR" -d "$t/classes" \
    $(find "$t/src" -name '*.java')
  [ -n "$(find "$t/classes" -name '*.class' | head -1)" ] || die "javac produced no classes"
  (cd "$t/classes" && jar cf "$t/classes.jar" .)
  "$BT/d8" --release --min-api "$min_api" --lib "$PLATFORM_JAR" --output "$t/dex" "$t/classes.jar"
  (cd "$t/dex" && zip -q "$t/dex.zip" classes.dex)
  apktool d -f -r "$t/dex.zip" -o "$t/smali-out" >/dev/null
  rm -rf "$out"; mkdir -p "$out"
  cp -r "$t/smali-out/smali/." "$out/"
  rm -rf "$t"
}

mkdir -p "$WORK_DIR" "$DIST_DIR"
