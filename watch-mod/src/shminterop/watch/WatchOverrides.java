package shminterop.watch;

import android.util.Log;

import com.samsung.android.hardware.sensormanager.SemSensorListener;

import shminterop.bp.BpAdapter;

import java.lang.reflect.Method;

/**
 * Vendor overrides on the watch side (SHM 1.5.1.022). Called from smali patched by the
 * superpatcher pattern watch-ecg-sensor-adapter.
 *
 * sensorAvailability(): availability exemption for the sensor wrapper (sa2.a) for IHRN
 * (0x25) and SpO2/apnea (0x31). Sensor 0x1b (ECG) is resolved earlier in the wrapper
 * (EcgAdapter) and never gets here.
 *
 * unregisterListener(): replaces every direct SemSensorManager.unregisterListener(listener, int)
 * call (pattern watch-sensor-unregister-compat). On One UI 8 Watch, hidden-API enforcement
 * denies that overload to a re-signed user app (api=blocked): the direct call surfaces as
 * NoSuchMethodError, and reflection via getMethods() also filters it out. Sensor 28 (BP) is
 * routed to BpAdapter.stop instead: that sensor is never registered with the framework manager
 * (BpAdapter owns it through the Health Tracking service), and sl.i must keep running to
 * unregister OffBody and cancel the timer.
 *
 * makeHrSensorAttribute(): builds a SemHrSensorAttribute by reflection for the BP calibration
 * path (pattern watch-bp-attribute-guard); returns null when hidden-API enforcement denies
 * the constructor, so SHM takes its normal sensor-error path instead of dying.
 */
public final class WatchOverrides {
    public static final String TAG = "ShmInterop";

    public static final int SENSOR_TYPE_IHRN = 0x25;   // 37 TYPE_WEAR_PPG_IHRN
    public static final int SENSOR_TYPE_SPO2 = 0x31;   // 49 SLEEP_WATCH_SPO2 (apnea)
    public static final int SENSOR_TYPE_BP = 0x1c;     // 28 manual BP (green PPG via BpAdapter)

    // Logged once per (type, HAL result) so logcat is not flooded (IhrnScheduler polls often).
    private static volatile int sSensorLogged;

    // The unregisterListener overload of this watch's framework, resolved on first use.
    private static volatile Method sUnregister;
    private static volatile boolean sUnregisterResolved;

    private static volatile boolean sHrAttributeFailureLogged;

    private WatchOverrides() {}

    /**
     * Unregisters [listener] for sensor [type] from [manager] (a SemSensorManager, typed Object so
     * no stub of it is needed) through whichever unregisterListener overload this framework has.
     * Never throws: a missing overload or a failed call is logged and the listener stays
     * registered, which is better than closing the app.
     */
    public static void unregisterListener(Object manager, SemSensorListener listener, int type) {
        if (type == SENSOR_TYPE_BP) {
            // Never registered with the framework manager, so there is nothing to unregister
            // there; BpAdapter tears down the Health Tracking tracker. sl.i keeps going (it
            // still has to unregister OffBody and cancel its timer).
            BpAdapter.stop(listener, type);
            return;
        }
        if (manager == null || listener == null) {
            return;
        }
        if (!sUnregisterResolved) {
            Method m = UnregisterMethod.pick(manager.getClass(), SemSensorListener.class);
            sUnregister = m;
            sUnregisterResolved = true;
            Log.i(TAG, "sensor: unregisterListener overload = " + (m == null ? "none" : m.toGenericString()));
        }
        Method m = sUnregister;
        if (m == null) {
            return;
        }
        try {
            UnregisterMethod.call(m, manager, listener, type);
        } catch (Throwable t) {
            Log.w(TAG, "sensor: unregisterListener(type " + type + ") failed: " + t);
        }
    }

    /**
     * Builds a com.samsung.android.hardware.sensormanager.SemHrSensorAttribute by reflection,
     * with no direct constructor reference, so a denied constructor cannot fail at class-link
     * time. Returns the instance, or null on any failure (LinkageError included), logged once.
     * The return type is Object because there is no SemHrSensorAttribute stub in watch-mod/stubs.
     */
    public static Object makeHrSensorAttribute() {
        Object attribute = HrSensorAttributeFactory.newInstance(
                "com.samsung.android.hardware.sensormanager.SemHrSensorAttribute");
        if (attribute == null && !sHrAttributeFailureLogged) {
            sHrAttributeFailureLogged = true;
            Log.w(TAG, "sensor: SemHrSensorAttribute construction failed (hidden-API enforcement"
                    + " may deny it on One UI 8 Watch); returning null");
        }
        return attribute;
    }

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
