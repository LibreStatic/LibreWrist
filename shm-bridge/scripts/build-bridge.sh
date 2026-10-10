#!/usr/bin/env bash
# Builds SHM Bridge (watch + phone) without Gradle: javac + d8 + aapt2 + apksigner.
# Output: $DIST_DIR/shm-bridge-{watch,phone}.apk. versionCode/versionName come from the manifest.
# The package marker `shminterop` is rewritten to PKG_PREFIX in a staging copy.
#   BRIDGE_ONLY=watch|phone builds only one (default: both)
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
source "$HERE/../scripts/lib/common.sh"
require_tools

# Optional: certificate digest of the patcher app (see config.env). Validated and normalized
# (lowercase hex, no colons) here so a malformed value fails the build instead of the manifest.
PATCHER_CERT=""
if [ -n "${PATCHER_CERT_SHA256:-}" ]; then
  PATCHER_CERT="$(printf '%s' "$PATCHER_CERT_SHA256" | tr -d ':' | tr 'A-F' 'a-f')"
  [[ "$PATCHER_CERT" =~ ^[0-9a-f]{64}$ ]] \
    || die "PATCHER_CERT_SHA256 must be 64 hex characters (colons optional)"
fi

mkdir -p "$WORK_DIR/bridge-build"

build_one() { # $1 = dir (watch|phone) $2 = minSdk $3 = targetSdk $4 = out apk
  local dir="$1" minsdk="$2" targetsdk="$3" out="$4"
  local b="$WORK_DIR/bridge-build/$dir"
  rm -rf "$b"; mkdir -p "$b/classes" "$b/dex"
  # Sources (src, res, manifest) with the prefix applied.
  python3 "$SHM_ROOT/scripts/lib/stage_prefix.py" "$HERE/$dir" "$b/stage" >/dev/null
  if [ "$dir" = "phone" ] && [ -n "$PATCHER_CERT" ]; then
    # StatusProvider's read permission: `signature` -> `signature|knownSigner` + knownCerts, so the
    # patcher app (signed with a different key) can read it. knownSigner is ignored below API 31
    # (the phone minSdk is 29): there only the bridge's own signature qualifies. StatusProvider
    # does not read knownCerts (hidden API): it gets the same digest from its PATCHER_CERT
    # meta-data, which is filled in here as well and checked with hasSigningCertificate (API 28+).
    python3 - "$b/stage/AndroidManifest.xml" "$PATCHER_CERT" <<'PYEOF'
import re, sys
path, cert = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
pat = re.compile(r'(<permission\b[^>]*android:name="[^"]*\.shmbridge\.permission\.STATUS"[^>]*?)'
                 r'android:protectionLevel="signature"')
s, n = pat.subn(r'\1android:protectionLevel="signature|knownSigner" android:knownCerts="' + cert + '"', s)
if n != 1:
    sys.exit('STATUS permission not found in the staged phone manifest')
meta = re.compile(r'(<meta-data\b[^>]*android:name="[^"]*\.shmbridge\.PATCHER_CERT"[^>]*?)android:value=""')
s, n = meta.subn(r'\1android:value="' + cert + '"', s)
if n != 1:
    sys.exit('PATCHER_CERT meta-data not found in the staged phone manifest')
open(path, 'w', encoding='utf-8').write(s)
PYEOF
  fi
  javac --release 11 -nowarn -encoding UTF-8 -classpath "$PLATFORM_JAR" -d "$b/classes" \
    $(find "$b/stage/src" -name '*.java')
  (cd "$b/classes" && jar cf "$b/classes.jar" .)
  local dexinputs=("$b/classes.jar")
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

echo "Install and grant permissions as described in shm-bridge/README.md (authoritative list: catalogue/catalogue.json \"grants\"; repeat the grants after every reinstall)."
