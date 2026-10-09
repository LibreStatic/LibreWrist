#!/usr/bin/env bash
# Regression oracle for the build output: compare the DECODED content of the APKs of two dist
# directories (e.g. the output of a build before and after a refactor of the patch engine).
#
#   scripts/oracle_decoded_diff.sh <distA> <distB>
#
# For every *.apk name present in both directories:
#   1. apktool d (serialised with the global apktool lock, heap from config.env) into
#      $WORK_DIR/oracle/A/<name> and $WORK_DIR/oracle/B/<name>;
#   2. delete original/META-INF (the signature files; everything else of original/ is kept);
#   3. diff -r of the two trees (smali, resources, manifest, assets, libs, apktool.yml);
#   4. compare the sorted (entry name, CRC-32, size) list of the two APK zips, META-INF/
#      excluded, which also covers the compiled parts (classes*.dex, resources.arsc, ...).
# No other normalisation is applied: any difference is reported and makes the script exit 1.
# Names present in only one directory are reported as a warning. The decoded trees are removed
# when everything matches (KEEP=1 keeps them); on a difference they stay for inspection.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

[ $# -eq 2 ] || die "usage: $0 <distA> <distB>"
A="$(cd "$1" && pwd)"; B="$(cd "$2" && pwd)"
OUT="$WORK_DIR/oracle"

zip_listing() {
  python3 - "$1" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for i in sorted(z.infolist(), key=lambda i: i.filename):
        if not i.filename.startswith('META-INF/'):
            print(i.filename, format(i.CRC, '08x'), i.file_size)
PY
}

names=()
for f in "$A"/*.apk; do
  n="$(basename "$f")"
  if [ -f "$B/$n" ]; then names+=("$n"); else echo "WARNING: $n is only in $A"; fi
done
for f in "$B"/*.apk; do
  [ -f "$A/$(basename "$f")" ] || echo "WARNING: $(basename "$f") is only in $B"
done
[ "${#names[@]}" -gt 0 ] || die "no APK name is present in both directories"

failed=0
for n in "${names[@]}"; do
  for side in A B; do
    src="$A"; [ "$side" = B ] && src="$B"
    rm -rf "${OUT:?}/$side/$n"; mkdir -p "$OUT/$side"
    apktool d -f "$src/$n" -o "$OUT/$side/$n" >/dev/null
    rm -rf "$OUT/$side/$n/original/META-INF"
  done
  status=OK
  if ! diff -rq "$OUT/A/$n" "$OUT/B/$n" >"$OUT/diff-$n.txt"; then
    status=DIFFERENT
    echo "== $n: decoded trees differ ($(wc -l <"$OUT/diff-$n.txt") entries, see $OUT/diff-$n.txt)"
    head -20 "$OUT/diff-$n.txt"
  fi
  if ! diff <(zip_listing "$A/$n") <(zip_listing "$B/$n") >"$OUT/zip-$n.txt"; then
    status=DIFFERENT
    echo "== $n: APK entry lists (name/CRC/size) differ (see $OUT/zip-$n.txt)"
    head -20 "$OUT/zip-$n.txt"
  fi
  echo "$n: $status"
  if [ "$status" = OK ]; then
    rm -f "$OUT/diff-$n.txt" "$OUT/zip-$n.txt"
    [ "${KEEP:-0}" = 1 ] || rm -rf "${OUT:?}/A/$n" "${OUT:?}/B/$n"
  else
    failed=$((failed + 1))
  fi
done

if [ "$failed" -gt 0 ]; then
  echo "ORACLE: $failed of ${#names[@]} APK(s) differ"
  exit 1
fi
echo "ORACLE: no differences in ${#names[@]} APK(s)"
