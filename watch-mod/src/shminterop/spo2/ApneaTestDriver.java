package shminterop.spo2;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debug-only sleep/wake driver for end-to-end testing of the apnea flow (inert unless the system
 * property {@code debug.shminterop.apnea_drive} is set before the app process first calls the
 * SpO2 adapter).
 *
 * The SHM apnea service reacts to sleep-state intents that are normally sent by the Health
 * Services build; there is no synthetic sleep there, and the service is guarded by a signature
 * permission so adb cannot start it. The SHM process itself can (same uid), so while the property
 * is set a daemon thread polls it every 3 s and, on every CHANGE of its value, sends the same
 * intent as the app's own sleep-state forwarder (smali c72, ApneaEmulatorManager):
 *
 *   sleep -> sleep_state 0 (SLEEPING),  wake -> sleep_state 1 (AWAKE),  anything else ignored.
 *
 * The first value read at start counts as a change. Setting the same value twice does nothing;
 * set another value (or empty) in between to repeat a transition.
 *
 * With the SpO2 simulation on ({@code debug.shminterop.spo2_sim=<hours>[:<events/h>]}) the
 * SLEEPING time is backdated by the simulated night's length, so the sleep window the apnea
 * library evaluates matches the synthetic data (it ignores sleep shorter than 1 h). AWAKE always
 * carries the current time.
 *
 * Android refuses startForegroundService from a background app, so SHM must be in the
 * foreground (open on the watch) when the property changes.
 */
public final class ApneaTestDriver {
    static final String TAG = "ApneaTestDriver";
    static final String PROP = "debug.shminterop.apnea_drive";
    static final String SERVICE_CLASS =
            "com.samsung.android.shealthmonitor.apnea.control.ApneaMonitoringService";
    static final String ACTION = "com.samsung.android.shealthmonitor.apnea.whs.activity";
    static final String EXTRA_STATE = "sleep_state";
    static final String EXTRA_TIME = "sleep_time";
    public static final int STATE_SLEEPING = 0;
    public static final int STATE_AWAKE = 1;
    public static final int STATE_NONE = -1;
    static final long POLL_MS = 3000L;

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private ApneaTestDriver() {}

    /**
     * Pure change tracking: feeds the successive property values, returns the sleep state to
     * send for each one (or STATE_NONE for no change / an unknown value).
     */
    public static final class Tracker {
        private String last = "";

        public int onValue(String value) {
            String v = value == null ? "" : value.trim();
            if (v.equals(last)) return STATE_NONE;
            last = v;
            return stateFor(v);
        }
    }

    /** "sleep" -> SLEEPING, "wake" -> AWAKE, anything else STATE_NONE. */
    public static int stateFor(String value) {
        if ("sleep".equals(value)) return STATE_SLEEPING;
        if ("wake".equals(value)) return STATE_AWAKE;
        return STATE_NONE;
    }

    /**
     * The sleep_time to send with [state]: now, or for SLEEPING with a valid simulation spec,
     * now minus the simulated night's length.
     */
    public static long eventTime(int state, long nowMs, String simSpec) {
        if (state != STATE_SLEEPING) return nowMs;
        int[] sim = SyntheticNight.parseSpec(simSpec);
        return sim == null ? nowMs : nowMs - sim[0] * 3600_000L;
    }

    /** Idempotent; called from every SpO2Adapter entry point. Never throws. */
    public static void ensureStarted() {
        if (!started.compareAndSet(false, true)) return;
        try {
            final String initial = SysProp.get(PROP);
            if (initial.isEmpty()) return; // inert: no thread at all
            Log.i(TAG, "driver enabled (" + PROP + "=" + initial + "); test-only");
            Thread t = new Thread(new Runnable() {
                @Override public void run() { loop(initial); }
            }, "ApneaTestDriver");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            Log.w(TAG, "start failed: " + t);
        }
    }

    private static void loop(String initial) {
        Tracker tracker = new Tracker();
        String value = initial;
        while (true) {
            try {
                act(value, tracker.onValue(value));
            } catch (Throwable t) {
                Log.w(TAG, "poll failed: " + t);
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            }
            value = SysProp.get(PROP);
        }
    }

    // Last unknown value logged, so a value left in place is reported once, not every poll.
    private static String sLastUnknown = "";

    private static void act(String value, int state) {
        if (state == STATE_NONE) {
            if (!value.isEmpty() && stateFor(value) == STATE_NONE && !value.equals(sLastUnknown)) {
                sLastUnknown = value;
                Log.i(TAG, "ignoring unknown value '" + value + "' (use sleep or wake)");
            }
            return;
        }
        sLastUnknown = "";
        Log.i(TAG, "value '" + value + "' -> sending sleep_state " + state);
        send(state);
    }

    /** Thin Android shim: the same intent as smali c72, class resolved by name at run time. */
    static void send(int state) {
        try {
            Context ctx = SpO2Adapter.appContext();
            if (ctx == null) {
                Log.w(TAG, "no application context; nothing sent");
                return;
            }
            Class<?> service = Class.forName(SERVICE_CLASS, true, ctx.getClassLoader());
            Intent intent = new Intent(ctx, service);
            intent.setAction(ACTION);
            intent.putExtra(EXTRA_STATE, state);
            long time = eventTime(state, System.currentTimeMillis(), SysProp.get(SpO2Adapter.SIM_PROP));
            intent.putExtra(EXTRA_TIME, time);
            ctx.startForegroundService(intent);
            Log.i(TAG, "sent sleep_state " + state + " (sleep_time " + time + ") to " + SERVICE_CLASS);
        } catch (Throwable t) {
            Log.w(TAG, "send failed: " + t);
        }
    }
}
