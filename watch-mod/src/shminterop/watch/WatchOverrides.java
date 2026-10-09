package shminterop.watch;

import android.util.Log;

/**
 * Vendor overrides on the watch side (SHM 1.5.1.022). Called from smali patched by the
 * superpatcher pattern watch-ecg-sensor-adapter.
 *
 * sensorAvailability(): availability exemption for the sensor wrapper (sa2.a) for IHRN
 * (0x25) and SpO2/apnea (0x31). Sensor 0x1b (ECG) is resolved earlier in the wrapper
 * (EcgAdapter) and never gets here.
 */
public final class WatchOverrides {
    public static final String TAG = "ShmInterop";

    public static final int SENSOR_TYPE_IHRN = 0x25;   // 37 TYPE_WEAR_PPG_IHRN
    public static final int SENSOR_TYPE_SPO2 = 0x31;   // 49 SLEEP_WATCH_SPO2 (apnea)

    // Logged once per (type, HAL result) so logcat is not flooded (IhrnScheduler polls often).
    private static volatile int sSensorLogged;

    private WatchOverrides() {}

    /**
     * Called at the end of sa2.a(I)Z with the real result of SemSensorManager.isAvailable(type).
     * For 0x25 (IHRN) and 0x31 (SpO2/apnea) returns true (availability exemption) and logs
     * the real HAL result; any other type passes through unchanged.
     */
    public static boolean sensorAvailability(int type, boolean halResult) {
        int bit;
        String name;
        if (type == SENSOR_TYPE_IHRN) {
            bit = halResult ? 1 : 2;
            name = "PPG_IHRN";
        } else if (type == SENSOR_TYPE_SPO2) {
            bit = halResult ? 4 : 8;
            name = "SLEEP_WATCH_SPO2";
        } else {
            return halResult;
        }
        if ((sSensorLogged & bit) == 0) {
            sSensorLogged |= bit;
            if (halResult) {
                Log.i(TAG, "sensor: HAL reports type " + type + " (" + name + ") available; no override");
            } else {
                Log.w(TAG, "sensor: HAL does NOT report type " + type + " (" + name
                        + ") (isAvailable=false or SemSensorManager null); availability exemption -> true."
                        + " If the measurement fails (registerListener=false / timeout), the HAL lacks this type");
            }
        }
        return true;
    }
}
