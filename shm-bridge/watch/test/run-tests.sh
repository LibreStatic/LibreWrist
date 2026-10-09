#!/usr/bin/env bash
# Host-side (JVM) tests for the watch bridge: compiles the pure-Java classes
# (no Android: FrameMux) together with the tests and runs them
# with loopback sockets standing in for RFCOMM.
# Runs against the sources with the `shminterop` marker (the prefix is irrelevant here).
#   MUX_TEST_VERBOSE=1 shm-bridge/watch/test/run-tests.sh   -> mux log + stack traces
set -euo pipefail
cd "$(dirname "$0")"

OUT="${MUX_TEST_OUT:-${WORK_DIR:-$HOME/.cache/shm-interop}/bridge-watch-test}"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

javac --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" \
  ../src/shminterop/shmbridge/watch/FrameMux.java \
  $(find src -name '*.java')

FAILED=0
java -Xmx512m -cp "$OUT/classes" shminterop.shmbridge.watch.FrameMuxTest || FAILED=1
exit "$FAILED"
