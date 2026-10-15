package shminterop.spo2;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.samsung.android.hardware.sensormanager.SemSensorAttribute;
import com.samsung.android.hardware.sensormanager.SemSensorListener;
import com.samsung.android.hardware.sensormanager.SemSleepWatchSpO2SensorEvent;

import shminterop.hsdk.HealthError;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;
import shminterop.hsdk.PpgTracker;
import shminterop.hsdk.ServiceProtocol;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Answers every use of the sleep SpO2 sensor (0x31, SLEEP_WATCH_SPO2) of the modified SHM watch
 * app, which a re-signed app cannot register with the framework (privileged). The smali hooks in
 * the sensor wrapper sa2 (pattern watch-spo2-sensor-adapter) call the static entry points below
 * instead of SemSensorManager; WatchOverrides.unregisterListener routes the same sensor type to
 * {@link #unregister}. The data is computed here from raw red/IR PPG of the Health Tracking
 * Service by {@link Spo2Estimator}. Design and decisions: docs/spo2-adapter.md.
 *
 * Protocol (SemSleepWatchSpO2SensorAttribute commands, bundle under key "cmd", an EnumSet read
 * by enum name):
 *   GET_VERSION          -> VERSION event with versionMap {1: version} (V1 only), async
 *   SET_OPERATION_MODE   -> start recording the night (tied to the calling listener)
 *   GET_DATA(start,end)  -> dense 1 Hz answer in NORMAL chunks, or one EMPTY event, async
 *   FLUSH                -> accepted, ignored (never sent by SHM)
 * Events always go to the listener passed to that setAttribute call.
 */
public final class SpO2Adapter {
    static final String TAG = "SpO2Adapter";
    static final int TYPE = 49; // 0x31

    /**
     * Feed the channel the service labels IR as "red" and the one labelled red as "IR". On the
     * owner's SM-R920 (ADI sensor path) the HTS labelling is inverted w.r.t. the algorithm's
     * convention: as labelled, R is about 1.75 at a stock-measured 97 %, which no physiology
     * explains; swapped, R is about 0.52, which maps to 98 % (on-wrist dataset, see
     * docs/spo2-adapter.md). Remove the swap if another model labels the channels the usual way.
     */
    static final boolean SWAP_RED_IR = true;

    static final String SIM_PROP = "debug.shminterop.spo2_sim";
    static final String PKG = "com.samsung.android.hardware.sensormanager.";
    static final String DATA_TYPE_CLASS = PKG + "SemSleepWatchSpO2SensorParam$DataType";

    // Version reported for both algorithm kinds (only the "a.b.c" shape and versionMap key 1 matter).
    static final int[] VERSION_CALCULATION = {1, 11, 1};
    static final int[] VERSION_SIGNALCHECK = {1, 0, 0};

    static final long RESUME_MS = 30L * 60L * 1000L;     // a restart this soon continues the night
    static final long LIVENESS_MS = 15000L;
    static final long WATCHDOG_TICK_MS = 5000L;
    static final long FLUSH_MS = 60000L;

    private static final Object LOCK = new Object();
    private static final Object FEED_LOCK = new Object();

    private static final Set<SemSensorListener> listeners =
            Collections.newSetFromMap(new IdentityHashMap<SemSensorListener, Boolean>());
    static final Spo2Store store = new Spo2Store();

    private static SemSensorListener recorder;
    private static PpgTracker tracker;
    private static ScheduledExecutorService watchdog;
    private static volatile boolean recording;
    private static volatile long lastBatchMs;
    private static long lastArmMs;
    private static long lastFlushMs;
    private static ExecutorService delivery;

    // Touched only under FEED_LOCK.
    private static Spo2Estimator estimator;
    private static int statSeconds, statValid;
    private static final List<Double> statR = new ArrayList<Double>();

    private static final Spo2Estimator.Output OUT = new Spo2Estimator.Output() {
        @Override public void onSecond(long secondEpochMs, int value, double r) {
            store.put(secondEpochMs, value);
            statSeconds++;
            if (value > 0) {
                statValid++;
                statR.add(r);
            }
        }
    };

    private static final PpgTracker.Listener CONN = new Conn();

    private SpO2Adapter() {}

    // ------------------------------------------------------------ entry points (from smali)

    /** sa2.c(listener, type) for type 0x31: remember the listener, report success. */
    public static boolean register(SemSensorListener listener, int type) {
        try {
            ApneaTestDriver.ensureStarted();
            log("register: listener=" + listener + " type=" + type);
            synchronized (LOCK) {
                if (listener != null) listeners.add(listener);
            }
        } catch (Throwable t) {
            log("register failed: " + t);
        }
        return true;
    }

    /**
     * sa2.d(listener, type, attribute) for type 0x31. Always returns true (also for commands it
     * does not know): the consumer only logs a false and gives up on the night.
     */
    public static boolean setAttribute(SemSensorListener listener, int type, SemSensorAttribute attribute) {
        try {
            ApneaTestDriver.ensureStarted();
            Bundle b = attribute == null ? null : attribute.getAttribute(TYPE);
            if (b == null) {
                log("setAttribute: no attribute bundle for type " + type + "; ignored");
                return true;
            }
            Set<String> cmds = commandNames(b.getSerializable("cmd"));
            log("setAttribute: listener=" + listener + " cmds=" + cmds);
            if (cmds.contains("SET_OPERATION_MODE")) {
                log("SET_OPERATION_MODE mode=" + b.getSerializable("mode"));
                startRecording(listener);
            }
            if (cmds.contains("GET_VERSION")) {
                sendVersion(listener, b.getSerializable("versionType"));
            }
            if (cmds.contains("GET_DATA")) {
                answerData(listener, b.getLong("startTimestamp"), b.getLong("endTimestamp"));
            }
            if (cmds.contains("FLUSH")) {
                log("FLUSH accepted (nothing to flush)");
            }
            if (cmds.isEmpty()) {
                log("setAttribute: no command in the bundle; ignored");
            }
        } catch (Throwable t) {
            log("setAttribute failed: " + t);
        }
        return true;
    }

    /** sa2.e(listener, type) and WatchOverrides.unregisterListener for type 0x31. */
    public static void unregister(SemSensorListener listener, int type) {
        try {
            log("unregister: listener=" + listener + " type=" + type);
            boolean wasRecorder;
            synchronized (LOCK) {
                listeners.remove(listener);
                wasRecorder = listener != null && listener == recorder;
                if (wasRecorder) recorder = null;
            }
            if (wasRecorder) stopRecording();
        } catch (Throwable t) {
            log("unregister failed: " + t);
        }
    }

    // ------------------------------------------------------------------- commands

    static Set<String> commandNames(Object cmd) {
        Set<String> out = new HashSet<String>();
        if (cmd instanceof Iterable) {
            for (Object o : (Iterable<?>) cmd) {
                if (o instanceof Enum) out.add(((Enum<?>) o).name());
                else if (o != null) out.add(String.valueOf(o));
            }
        } else if (cmd instanceof Enum) {
            out.add(((Enum<?>) cmd).name());
        }
        return out;
    }

    static void sendVersion(SemSensorListener listener, Object versionType) {
        Bundle b = versionBundle(versionType);
        if (b == null) {
            log("GET_VERSION: DataType enum not available; no event sent");
            return;
        }
        deliver(listener, Collections.singletonList(b));
    }

    /** The VERSION event content (null when the vendor DataType enum cannot be resolved). */
    static Bundle versionBundle(Object versionType) {
        Object dataType = enumConstant(DATA_TYPE_CLASS, "VERSION");
        if (dataType == null) return null;
        String name = versionType instanceof Enum ? ((Enum<?>) versionType).name() : "";
        int[] version = "CALCULATION_ALGO_VERSION".equals(name)
                ? VERSION_CALCULATION.clone() : VERSION_SIGNALCHECK.clone();
        HashMap<Integer, int[]> map = new HashMap<Integer, int[]>();
        map.put(1, version.clone()); // V1 only: never a key 2
        Bundle b = new Bundle();
        b.putSerializable("data_type", (java.io.Serializable) dataType);
        if (versionType instanceof java.io.Serializable) {
            b.putSerializable("version_type", (java.io.Serializable) versionType);
        }
        b.putIntArray("version", version);
        b.putSerializable("versionMap", map);
        return b;
    }

    static void answerData(SemSensorListener listener, long startMs, long endMs) {
        List<Bundle> events = dataEvents(startMs, endMs);
        log("GET_DATA " + startMs + ".." + endMs + " -> " + events.size() + " event(s)");
        deliver(listener, events);
    }

    /** The events answering GET_DATA: NORMAL chunks, or a single EMPTY event. */
    static List<Bundle> dataEvents(long startMs, long endMs) {
        List<Bundle> events = new ArrayList<Bundle>();
        int[] sim = SyntheticNight.parseSpec(SysProp.get(SIM_PROP));
        List<Spo2Answer.Chunk> chunks;
        if (sim != null) {
            final int[] night = SyntheticNight.generate(sim[0], sim[1]);
            final long last = Math.floorDiv(endMs, 1000L) * 1000L;
            final long first = last - (night.length - 1) * 1000L;
            log("SIMULATION: answering GET_DATA with a synthetic night of " + sim[0] + " h, "
                    + sim[1] + " events/h (" + SIM_PROP + "), ending at " + last
                    + "; recorded data ignored");
            chunks = Spo2Answer.build(first, last, new Spo2Answer.Source() {
                @Override public int valueAt(long secondMs) {
                    long i = (secondMs - first) / 1000L;
                    return i >= 0 && i < night.length ? night[(int) i] : 0;
                }
            });
        } else {
            chunks = Spo2Answer.build(startMs, endMs, new Spo2Answer.Source() {
                @Override public int valueAt(long secondMs) { return store.get(secondMs); }
            });
        }
        Object normal = enumConstant(DATA_TYPE_CLASS, "NORMAL");
        Object empty = enumConstant(DATA_TYPE_CLASS, "EMPTY");
        if (chunks.isEmpty()) {
            if (empty != null) {
                Bundle b = new Bundle();
                b.putSerializable("data_type", (java.io.Serializable) empty);
                events.add(b);
            }
            return events;
        }
        if (normal == null) return events;
        for (Spo2Answer.Chunk c : chunks) events.add(chunkBundle(normal, c));
        return events;
    }

    static Bundle chunkBundle(Object normalType, Spo2Answer.Chunk c) {
        Bundle b = new Bundle();
        b.putSerializable("data_type", (java.io.Serializable) normalType);
        b.putInt("secFullSize", c.fullSize);
        b.putInt("secSize", c.size);
        b.putInt("secRemainCount", c.remainCount);
        b.putIntArray("secSpO2List", c.spo2);
        b.putLongArray("secTimeList", c.times);
        // V1 ignores them and the V2 consumer null-checks them; their encoding is unspecified
        // (docs/spo2-calibration.md 4.4), so zeros of the right length.
        b.putShortArray("dcRatioList", new short[c.size]);
        b.putShortArray("accPowerList", new short[c.size]);
        return b;
    }

    /** Sequential asynchronous delivery of the events to one listener, first one immediately. */
    static void deliver(final SemSensorListener listener, final List<Bundle> events) {
        if (listener == null || events.isEmpty()) return;
        delivery().execute(new Runnable() {
            private boolean linkageLogged;

            @Override public void run() {
                for (Bundle b : events) {
                    try {
                        listener.onEventChanged(new SemSleepWatchSpO2SensorEvent(TYPE, b));
                    } catch (Throwable t) {
                        if (t instanceof LinkageError && !linkageLogged) {
                            linkageLogged = true;
                            log("deliver failed: hidden-API enforcement may deny the"
                                    + " SemSleepWatchSpO2SensorEvent constructor (" + t + ")");
                        } else {
                            log("deliver failed: " + t);
                        }
                    }
                }
            }
        });
    }

    private static synchronized ExecutorService delivery() {
        if (delivery == null) {
            delivery = Executors.newSingleThreadExecutor(daemons("SpO2Adapter-deliver"));
        }
        return delivery;
    }

    private static ThreadFactory daemons(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    static Object enumConstant(String className, String name) {
        try {
            Object[] all = Class.forName(className).getEnumConstants();
            if (all != null) {
                for (Object o : all) {
                    if (((Enum<?>) o).name().equals(name)) return o;
                }
            }
        } catch (Throwable t) {
            log("enum " + className + " unavailable: " + t);
        }
        return null;
    }

    // ------------------------------------------------------------------- recording

    static void startRecording(SemSensorListener listener) {
        synchronized (LOCK) {
            recorder = listener;
            if (recording) {
                log("start: already recording; now tied to " + listener);
                return;
            }
            Context ctx = appContext();
            if (ctx != null) {
                try {
                    store.attach(new File(new File(ctx.getFilesDir(), "shminterop-spo2"), "night.bin"));
                } catch (Throwable t) {
                    log("no persistence: " + t);
                }
            }
            if (store.size() == 0) store.load();
            long now = System.currentTimeMillis();
            if (store.size() > 0 && now - store.newest() <= RESUME_MS) {
                log("start: resuming the night (" + store.size() + " s stored, newest "
                        + (now - store.newest()) / 1000 + " s ago)");
            } else {
                store.clear();
                log("start: new night");
            }
            synchronized (FEED_LOCK) {
                estimator = new Spo2Estimator(OUT);
                statSeconds = 0;
                statValid = 0;
                statR.clear();
            }
            recording = true;
            long e = SystemClock.elapsedRealtime();
            lastBatchMs = 0L;
            lastArmMs = e;
            lastFlushMs = e;
            startTracker(ctx);
            startWatchdog();
        }
    }

    static void stopRecording() {
        synchronized (LOCK) {
            if (!recording) return;
            log("stop recording (" + store.size() + " s stored)");
            recording = false;
            stopTracker();
            ScheduledExecutorService w = watchdog;
            watchdog = null;
            if (w != null) w.shutdownNow();
            logStats();
            store.flush();
        }
    }

    private static void startTracker(Context ctx) {
        if (ctx == null) {
            log("no application context; the PPG tracker cannot start");
            return;
        }
        try {
            tracker = new PpgTracker(ctx,
                    Arrays.asList(ServiceProtocol.PPG_TYPE_RED, ServiceProtocol.PPG_TYPE_IR), CONN);
            tracker.start();
        } catch (Throwable t) {
            log("tracker start failed: " + t);
            tracker = null;
        }
    }

    private static void stopTracker() {
        PpgTracker t = tracker;
        tracker = null;
        if (t != null) {
            try { t.stop(); } catch (Throwable ignored) {}
        }
    }

    private static void startWatchdog() {
        ScheduledExecutorService old = watchdog;
        if (old != null) old.shutdownNow();
        watchdog = Executors.newSingleThreadScheduledExecutor(daemons("SpO2Adapter-watchdog"));
        watchdog.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try { tick(); } catch (Throwable t) { log("watchdog: " + t); }
            }
        }, WATCHDOG_TICK_MS, WATCHDOG_TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** Liveness re-arm, periodic flush and median-R log; package-visible for tests. */
    static void tick() {
        synchronized (LOCK) {
            if (!recording) return;
            long e = SystemClock.elapsedRealtime();
            long ref = Math.max(lastBatchMs, lastArmMs);
            if (e - ref > LIVENESS_MS) {
                log("no PPG batch for " + (e - ref) / 1000 + " s; re-arming the tracker");
                stopTracker();
                lastArmMs = e;
                startTracker(appContext());
            }
            if (e - lastFlushMs >= FLUSH_MS) {
                lastFlushMs = e;
                store.flush();
                logStats();
            }
        }
    }

    private static void logStats() {
        synchronized (FEED_LOCK) {
            double med = Double.NaN;
            if (!statR.isEmpty()) {
                List<Double> s = new ArrayList<Double>(statR);
                Collections.sort(s);
                med = s.get(s.size() / 2);
            }
            log(String.format("stats: valid %d/%d s, median R %.3f (swap=%b), stored %d s",
                    statValid, statSeconds, med, SWAP_RED_IR, store.size()));
            statSeconds = 0;
            statValid = 0;
            statR.clear();
        }
    }

    /** Feeds one service sample to the estimator, applying the channel orientation. */
    static void feed(Spo2Estimator est, PpgSample s) {
        int red = SWAP_RED_IR ? s.irAdc : s.redAdc;
        int ir = SWAP_RED_IR ? s.redAdc : s.irAdc;
        est.add(s.timestampMs, red, ir, s.isRedNormal() && s.isIrNormal());
    }

    static final class Conn implements PpgTracker.Listener {
        @Override public void onArmed() { log("tracker armed (RED, IR)"); }

        @Override public void onUnsupported() { log("PPG_ON_DEMAND not supported by the health service"); }

        @Override public void onConnectionFailed(String reason) { log("onConnectionFailed " + reason); }

        @Override public void onConnectionEnded() { log("onConnectionEnded"); }

        @Override public void onError(HealthError error) { log("onError " + error); }

        @Override public void onBatch(PpgBatch batch) {
            if (!recording || batch == null) return;
            lastBatchMs = SystemClock.elapsedRealtime();
            synchronized (FEED_LOCK) {
                Spo2Estimator est = estimator;
                if (est == null) return;
                for (PpgSample s : batch.samples) feed(est, s);
            }
        }
    }

    // ------------------------------------------------------------------------ utils

    /** Package-visible for the tests: the tracker callbacks the adapter registered. */
    static PpgTracker.Listener trackerListener() {
        return CONN;
    }

    static boolean isRecording() {
        return recording;
    }

    /** Back to the idle state (tests). */
    static void resetForTest() {
        synchronized (LOCK) {
            recording = false;
            recorder = null;
            listeners.clear();
            stopTracker();
            ScheduledExecutorService w = watchdog;
            watchdog = null;
            if (w != null) w.shutdownNow();
        }
        synchronized (FEED_LOCK) {
            estimator = null;
            statSeconds = 0;
            statValid = 0;
            statR.clear();
        }
        store.attach(null);
        store.clear();
    }

    static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static void log(String s) {
        Log.i(TAG, s);
    }
}
