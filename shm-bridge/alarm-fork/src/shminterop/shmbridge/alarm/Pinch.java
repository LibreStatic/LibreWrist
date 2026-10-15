package shminterop.shmbridge.alarm;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.samsung.android.lib_algorithm.gesture.samsung.NativeWrapper;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Double pinch detector of the Samsung Alarm app fork. The vendor gesture manager is not reachable
 * for the re-signed app (SELinux), so this feeds linear acceleration and gyroscope samples to the
 * public device library {@code lib_algorithm.gesture.samsung.so} and, on a detection, calls the
 * app's own {@code SmartGestureEvent} listener with {@code QUICKACTION} (the app decides between
 * snooze and dismiss). Without the library or the sensors nothing happens and the alarm is unaffected.
 */
public final class Pinch {

    static final String TAG = "SHMBridgeAlarmPinch";
    static final int MODE_CONTROL = 1;
    static final int GESTURE_QUICKACTION = 256;
    static final int WINDOW = 8;
    static final int MAX_QUEUE = 1200;
    static final int SAMPLE_US = 10_000;
    static final long DEBOUNCE_MS = 1500;
    static final float RAD_TO_DEG = 57.2958f;

    private static final String LIB = "_algorithm.gesture.samsung";
    private static final String TYPE_CLASS = "com.samsung.android.hardware.gesturemanager.SmartGestureType";
    private static final String EVENT_CLASS = "com.samsung.android.hardware.gesturemanager.SmartGestureEvent";

    /** Serializes the library calls: its state is global, and an old session may still be running. */
    private static final Object NATIVE_LOCK = new Object();
    private static Boolean sLoaded;
    private static volatile Session sActive;

    private Pinch() {}

    /** Starts detection and reports hits to {@code gestureListener}; replaces any previous session. */
    public static synchronized void start(Context ctx, Object gestureListener) {
        stop();
        try {
            if (gestureListener == null) return;
            if (sLoaded == null) {
                try {
                    System.loadLibrary(LIB);
                    sLoaded = Boolean.TRUE;
                } catch (Throwable t) {
                    sLoaded = Boolean.FALSE;
                    Log.w(TAG, "gesture library unavailable, double pinch disabled: " + t);
                }
            }
            if (!sLoaded) return;
            Session s = new Session(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx,
                    gestureListener);
            if (!s.begin()) return;
            sActive = s;
            Log.i(TAG, "start orient=" + s.orient + " model=" + s.model);
        } catch (Throwable t) {
            Log.w(TAG, "start failed", t);
        }
    }

    /** Stops detection. */
    public static synchronized void stop() {
        Session s = sActive;
        sActive = null;
        if (s == null) return;
        Log.i(TAG, "stop");
        try {
            s.end();
        } catch (Throwable t) {
            Log.w(TAG, "stop failed", t);
        }
    }

    /**
     * Value of the app's off-body sensor (0 = off the wrist, 1 = on it), as the app receives it
     * while ringing: detection pauses off the wrist and resumes, from a fresh state, on it.
     */
    public static synchronized void onBody(int value) {
        Session s = sActive;
        if (s == null || (value != 0 && value != 1)) return;
        boolean pause = value == 0;
        if (s.paused == pause) return;
        if (!pause) s.resetPending = true;
        s.paused = pause;
        Log.i(TAG, pause ? "paused (off the wrist)" : "resumed (on the wrist)");
    }

    /** Lock-free (read under the native lock by the sensor thread). */
    private static boolean isActive(Session s) {
        return sActive == s;
    }

    // ---- pure helpers (no Android dependencies) ----

    /** Orientation input of the library from the watch wrist and button settings. */
    static int orient(int wrist, int button) {
        return wrist * 2 + (button == 1 ? 1 : 0);
    }

    /** Model type of the library from {@code Build.MODEL}. */
    static int modelType(String model) {
        if (model == null) return 0;
        if (model.contains("SM-L70")) return 1;
        if (model.contains("SM-L50")) return 2;
        return 0;
    }

    /** Packs {@link #WINDOW} samples of each sensor into the library's {@code float[60]} input. */
    static void pack(float[] buf, float[][] lacc, float[][] gyro) {
        Arrays.fill(buf, 0f);
        for (int i = 0; i < WINDOW; i++) {
            for (int k = 0; k < 3; k++) {
                buf[WINDOW * k + i] = lacc[i][k];
                buf[WINDOW * (3 + k) + i] = gyro[i][k];
            }
        }
    }

    /** Whether a result of the library is a double pinch. */
    static boolean isHit(NativeWrapper r) {
        return r != null && r.status && r.detGesture == GESTURE_QUICKACTION;
    }

    /** Whether a hit at {@code now} must be delivered, given the last delivered one (or 0 for none). */
    static boolean pastDebounce(long now, long lastDelivered) {
        return lastDelivered == 0 || now - lastDelivered >= DEBOUNCE_MS;
    }

    private static void push(ArrayDeque<float[]> q, float[] v, float scale) {
        if (q.size() >= MAX_QUEUE) q.poll();
        q.add(new float[] {v[0] * scale, v[1] * scale, v[2] * scale});
    }

    // ---- session ----

    private static final class Session implements SensorEventListener {
        final Context ctx;
        final Object listener;
        final int orient;
        final int model;
        final ArrayDeque<float[]> lacc = new ArrayDeque<>();
        final ArrayDeque<float[]> gyro = new ArrayDeque<>();
        final float[] buf = new float[60];
        final float[][] wl = new float[WINDOW][];
        final float[][] wg = new float[WINDOW][];
        volatile NativeWrapper nw;
        SensorManager sm;
        HandlerThread thread;
        Handler main;
        long lastDelivered;
        /** Set by {@link Pinch#onBody}; samples are dropped while paused. */
        volatile boolean paused;
        /** Set on resume; the sensor thread then drops the queued samples and resets the library. */
        volatile boolean resetPending;

        Session(Context ctx, Object listener) {
            this.ctx = ctx;
            this.listener = listener;
            int wrist = Settings.Global.getInt(ctx.getContentResolver(), "setting_watch_device_wrist_position", 0);
            int button = Settings.Global.getInt(ctx.getContentResolver(), "setting_watch_device_button_position", 1);
            this.orient = Pinch.orient(wrist, button);
            this.model = Pinch.modelType(Build.MODEL);
        }

        boolean begin() {
            sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            Sensor a = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            Sensor g = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (a == null || g == null) {
                Log.w(TAG, "linear acceleration or gyroscope missing, double pinch disabled");
                return false;
            }
            nw = new NativeWrapper();
            synchronized (NATIVE_LOCK) {
                nw.nativeReset(MODE_CONTROL, model, false);
            }
            main = new Handler(Looper.getMainLooper());
            thread = new HandlerThread("SHMBridgeAlarmPinch");
            thread.start();
            Handler h = new Handler(thread.getLooper());
            boolean ok = sm.registerListener(this, a, SAMPLE_US, h) && sm.registerListener(this, g, SAMPLE_US, h);
            if (!ok) {
                Log.w(TAG, "could not register the sensors, double pinch disabled");
                end();
                return false;
            }
            return true;
        }

        /** Called off the sensor thread: the queues stay as they are (that thread owns them). */
        void end() {
            if (sm != null) sm.unregisterListener(this);
            if (thread != null) thread.quitSafely();
            sm = null;
            thread = null;
            nw = null;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            try {
                if (paused) return;
                if (resetPending) {
                    resetPending = false;
                    lacc.clear();
                    gyro.clear();
                    NativeWrapper r = nw;
                    synchronized (NATIVE_LOCK) {
                        if (r == null || !isActive(this)) return;
                        r.nativeReset(MODE_CONTROL, model, false);
                    }
                }
                int t = e.sensor.getType();
                if (t == Sensor.TYPE_LINEAR_ACCELERATION) {
                    push(lacc, e.values, 1f);
                } else if (t == Sensor.TYPE_GYROSCOPE) {
                    push(gyro, e.values, RAD_TO_DEG);
                } else {
                    return;
                }
                NativeWrapper n = nw;
                if (n == null || lacc.size() < WINDOW || gyro.size() < WINDOW) return;
                for (int i = 0; i < WINDOW; i++) {
                    wl[i] = lacc.poll();
                    wg[i] = gyro.poll();
                }
                pack(buf, wl, wg);
                boolean hit;
                synchronized (NATIVE_LOCK) {
                    if (!isActive(this)) return;
                    hit = isHit(n.nativeRun(MODE_CONTROL, buf, orient, model));
                }
                if (!hit) return;
                long now = android.os.SystemClock.elapsedRealtime();
                if (!pastDebounce(now, lastDelivered)) return;
                lastDelivered = now;
                Log.i(TAG, "double pinch");
                main.post(() -> {
                    if (isActive(this) && !paused) deliver(listener);
                });
            } catch (Throwable t) {
                Log.w(TAG, "sample failed", t);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {}
    }

    /** Calls {@code SmartGestureEvent.onGestureEvent(SmartGestureType.QUICKACTION)} on the app's listener. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void deliver(Object listener) {
        try {
            Class t = Class.forName(TYPE_CLASS);
            Object qa = Enum.valueOf(t, "QUICKACTION");
            Class.forName(EVENT_CLASS).getMethod("onGestureEvent", t).invoke(listener, qa);
        } catch (Throwable t) {
            Log.w(TAG, "could not deliver the gesture", t);
        }
    }
}
