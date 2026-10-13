#!/usr/bin/env bash
# Host-side (JVM) tests: the pure-Java protocol classes of shminterop.hsdk (no Android types; the
# parcel is replaced by an in-memory fake) and the unregisterListener overload choice of
# shminterop.watch.UnregisterMethod.
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
  ../src/shminterop/watch/UnregisterMethod.java \
  ../src/shminterop/watch/HrSensorAttributeFactory.java \
  $(find src -name '*.java')

FAILED=0
java -Xmx256m -cp "$OUT/classes" shminterop.hsdk.ServiceProtocolTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.watch.UnregisterMethodTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.watch.HrSensorAttributeFactoryTest || FAILED=1
exit "$FAILED"
