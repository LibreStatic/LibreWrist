#!/usr/bin/env bash
# Build the watch Alarm app fork (receives phone alarms from the SHM bridge).
#
#   scripts/build_alarm_fork.sh
#
# official AlarmWatch APK (sha256 verified) -> shm-bridge/alarm-fork (Link, LinkActivity,
# LinkReceiver) compiled to smali -> superpatcher (catalogue/catalogue.json, profile alarm)
# injecting it as a new smali_classesN dir -> apktool b -> sign ->
# $DIST_DIR/alarm-fork-1.3.05.9.apk
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
require_tools

OFFICIAL="$(official_apk alarm-watch-1.3.05.9.apk)"
W="$WORK_DIR/build-alarm-fork"
rm -rf "$W"; mkdir -p "$W"

FORK_SMALI="$W/fork-smali"
java_to_smali "$SHM_ROOT/shm-bridge/alarm-fork/src" "$FORK_SMALI" 11 34

python3 "$SHM_ROOT/scripts/superpatcher.py" patch "$OFFICIAL" --profile alarm \
  -o "$W" --name alarm-fork-1.3.05.9 --inject alarm-fork="$FORK_SMALI"

OUT="$DIST_DIR/alarm-fork-1.3.05.9.apk"
sign_apk "$W/alarm-fork-1.3.05.9-unsigned.apk" "$OUT"
rm -rf "$W"
echo "OK: $OUT"
