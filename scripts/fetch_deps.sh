#!/usr/bin/env bash
# Downloads the third-party build dependencies into third_party/ and verifies their hashes.
#   - apktool 3.0.3 (GitHub release)
#   - play-services-wearable 19.0.0 AAR (Google Maven)
# Nothing is redistributed by this repo: third_party/*.jar and *.aar are gitignored.
set -euo pipefail
SHM_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$SHM_ROOT/third_party"
mkdir -p "$DEST"

APKTOOL_URL="https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar"
APKTOOL_SHA256="dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"
PSW_URL="https://dl.google.com/android/maven2/com/google/android/gms/play-services-wearable/19.0.0/play-services-wearable-19.0.0.aar"
PSW_SHA256="0d19788ce6d51cbf670890cfd4daf4633322247b116612f898e3a2ead8539000"

sha256_of() { sha256sum "$1" | awk '{print $1}'; }

# fetch <url> <dest file> <expected sha256> <strict: yes|no>
fetch() {
  local url="$1" out="$2" want="$3" strict="$4" got
  if [ -f "$out" ] && [ "$(sha256_of "$out")" = "$want" ]; then
    echo "ok (already present): $out"
    return 0
  fi
  echo "downloading $url"
  curl -fL --retry 3 -o "$out.part" "$url"
  got="$(sha256_of "$out.part")"
  if [ "$got" != "$want" ]; then
    if [ "$strict" = yes ]; then
      rm -f "$out.part"
      echo "error: sha256 mismatch for $(basename "$out")" >&2
      echo "  expected: $want" >&2
      echo "  got:      $got" >&2
      exit 1
    fi
    echo "warning: sha256 of $(basename "$out") differs from the expected value" >&2
    echo "  expected: $want" >&2
    echo "  got:      $got" >&2
  fi
  mv "$out.part" "$out"
  echo "saved: $out"
}

fetch "$APKTOOL_URL" "$DEST/apktool_3.0.3.jar" "$APKTOOL_SHA256" yes
fetch "$PSW_URL" "$DEST/play-services-wearable-19.0.0.aar" "$PSW_SHA256" no
