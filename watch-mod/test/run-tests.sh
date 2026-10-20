#!/usr/bin/env bash
# Host-side (JVM) tests: the pure-Java protocol classes of shminterop.hsdk (no Android types; the
# parcel is replaced by an in-memory fake), the unregisterListener overload choice of
# shminterop.watch.UnregisterMethod, and the BpAdapter repackaging layer (the Android and vendor
# types come from behavioural doubles in the test tree and PpgTracker from a shadow; the real
# tracker needs the binder stack only a device has), the IHRN adapter (pairing, flags, modes, sessions) and the foreground-start fallback of WatchOverrides, and the SpO2 adapter pieces
# (estimator, synthetic night, answer chunking, store, flow with doubles).
#   watch-mod/test/run-tests.sh
set -euo pipefail
cd "$(dirname "$0")"

OUT="${HSDK_TEST_OUT:-${WORK_DIR:-$HOME/.cache/shm-interop}/watch-mod-test}"
rm -rf "$OUT"
mkdir -p "$OUT/classes"

H=../src/shminterop/hsdk
javac --release 11 -nowarn -encoding UTF-8 -d "$OUT/classes" \
  $H/WireReader.java $H/WireWriter.java $H/WireFormatException.java $H/HealthError.java \
  $H/AccelSample.java $H/AccelBatch.java \
  $H/EcgSample.java $H/EcgBatch.java $H/PpgSample.java $H/PpgBatch.java $H/ServiceProtocol.java \
  ../src/shminterop/watch/UnregisterMethod.java \
  ../src/shminterop/watch/HrSensorAttributeFactory.java \
  ../src/shminterop/watch/ForegroundRequired.java \
  ../src/shminterop/watch/WatchOverrides.java \
  ../src/shminterop/ecg/LeadDebouncer.java ../src/shminterop/ecg/BeatMonitor.java \
  $(find ../src/shminterop/ihrn -name '*.java') \
  $(find ../src/shminterop/bp -name '*.java') \
  $(find ../src/shminterop/spo2 -name '*.java') \
  $(find src -name '*.java')

FAILED=0
java -Xmx256m -cp "$OUT/classes" shminterop.hsdk.ServiceProtocolTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.hsdk.ServiceProtocolPpgTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.hsdk.ServiceProtocolAccelTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.ecg.LeadDebouncerTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.ecg.BeatMonitorTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.ihrn.IhrnAdapterTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.watch.WatchOverridesServiceTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.watch.UnregisterMethodTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.watch.HrSensorAttributeFactoryTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.bp.BpAdapterTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.spo2.Spo2EstimatorTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.spo2.SyntheticNightTest || FAILED=1
java -Xmx256m -Dtest.tmp="$OUT" -cp "$OUT/classes" shminterop.spo2.Spo2AnswerTest || FAILED=1
java -Xmx256m -cp "$OUT/classes" shminterop.spo2.ApneaTestDriverTest || FAILED=1
java -Xmx256m -Dtest.tmp="$OUT" -cp "$OUT/classes" shminterop.spo2.SpO2AdapterTest || FAILED=1
exit "$FAILED"
