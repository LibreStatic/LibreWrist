#!/usr/bin/env bash
# Host-side (JVM) tests for the Health Tracking client: compiles the pure-Java protocol classes of
# shminterop.hsdk (no Android types) together with the tests and runs them. The parcel is replaced
# by an in-memory fake.
#   watch-mod/test/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")"

OUT="${HSDK_TEST_OUT:-${WORK_DIR:-$HOME/.cache/shm-interop}/watch-mod-test}"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

H=../src/shminterop/hsdk
javac --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" \
  $H/WireReader.java $H/WireWriter.java $H/WireFormatException.java $H/HealthError.java \
  $H/EcgSample.java $H/EcgBatch.java $H/ServiceProtocol.java \
  $(find src -name '*.java')

FAILED=0
java -Xmx256m -cp "$OUT/classes" shminterop.hsdk.ServiceProtocolTest || FAILED=1
exit "$FAILED"
