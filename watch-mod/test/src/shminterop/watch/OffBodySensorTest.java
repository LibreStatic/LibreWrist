package shminterop.watch;

import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * Plain main-based tests of WatchOverrides.offBodySensor (run-tests.sh only): Samsung's off-body
 * detector is replaced by the public one when the watch has it, kept when it does not, and every
 * other type passes through with the overload SHM called. Exits non-zero on failure.
 */
public final class OffBodySensorTest {
    private static int failures;

    public static void main(String[] args) {
        publicDetectorReplacesSamsungOne();
        samsungOneKeptWithoutPublicDetector();
        otherTypesPassThrough();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void eq(Object expected, Object actual, String what) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            failures++;
            System.out.println("FAIL: " + what + " expected=" + expected + " actual=" + actual);
        }
    }

    static SensorManager manager(boolean withPublic) {
        SensorManager sm = new SensorManager();
        sm.sensors.put(0x1101b, new Sensor(0x1101b, "Samsung LowPower Offbody Detector"));
        if (withPublic) sm.sensors.put(34, new Sensor(34, "Low Latency Off-Body Detect"));
        sm.sensors.put(1, new Sensor(1, "Accelerometer"));
        return sm;
    }

    static void publicDetectorReplacesSamsungOne() {
        SensorManager sm = manager(true);
        eq(34, WatchOverrides.offBodySensor(sm, 0x1101b, true).getType(), "IHRN lookup (type, wakeUp)");
        eq(34, WatchOverrides.offBodySensor(sm, 0x1101b).getType(), "BP lookup (type)");
    }

    static void samsungOneKeptWithoutPublicDetector() {
        SensorManager sm = manager(false);
        eq(0x1101b, WatchOverrides.offBodySensor(sm, 0x1101b, true).getType(), "IHRN fallback");
        eq("getDefaultSensor(69659, true)", sm.lastCall, "IHRN fallback keeps the wake-up overload");
        eq(0x1101b, WatchOverrides.offBodySensor(sm, 0x1101b).getType(), "BP fallback");
        eq("getDefaultSensor(69659)", sm.lastCall, "BP fallback keeps the plain overload");
    }

    static void otherTypesPassThrough() {
        SensorManager sm = manager(true);
        eq(1, WatchOverrides.offBodySensor(sm, 1, false).getType(), "other type, wakeUp overload");
        eq("getDefaultSensor(1, false)", sm.lastCall, "other type keeps its overload");
        eq(null, WatchOverrides.offBodySensor(sm, 99), "missing type stays null");
    }
}
