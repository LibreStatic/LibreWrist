package shminterop.ihrn;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import com.samsung.android.hardware.sensormanager.SemPpgIhrnSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorAttribute;
import com.samsung.android.hardware.sensormanager.SemSensorListener;

import shminterop.hsdk.AccelBatch;
import shminterop.hsdk.AccelSample;
import shminterop.hsdk.GreenAccelTracker;
import shminterop.hsdk.HealthError;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;
import shminterop.watch.WatchOverrides;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds the irregular-heart-rhythm (IHRN, sensor 0x25) pipeline of the modified SHM watch app,
 * which a re-signed app cannot register with the framework (privileged), from the green PPG and
 * accelerometer trackers of the Health Tracking Service. The smali hooks in the sensor wrapper
 * sa2 (pattern watch-ihrn-sensor-adapter) call {@link #register} and {@link #setAttribute};
 * WatchOverrides.unregisterListener routes the unregister here. Design, the SHM-side contract
 * and the open device questions: docs/ihrn-adapter.md.
 *
 * Flow (SHM side): after register SHM expects single-sample events (count 1) until it sends the
 * batch-mode attribute (BatchMode.MAX); from then on events carry exactly 250 samples, with
 * data_sequence +1 per event. Nothing is invented: a sample that cannot be trusted is delivered
 * with agc = true (see {@link IhrnConverter}), never replaced and never padded.
 *
 * Concurrency: every registration is a {@link Session} with its own single delivery thread; the
 * tracker callbacks (binder threads) only enqueue onto it. A session that is no longer the
 * current one delivers nothing, so late callbacks of a stopped session cannot reach the next.
 */
public final class IhrnAdapter {
    static final String TAG = WatchOverrides.TAG;
    static final int TYPE = 0x25;
    /** Samples per event in batch mode (SHM accepts exactly this count). */
    static final int BATCH = 250;
    /** Interval of the hub flush (the hub batches up to 300 samples; SHM's watchdog allows 5 s). */
    static final long FLUSH_MS = 1000L;
    /** Interval at which held green samples are re-examined for the pairing timeout. */
    static final long TICK_MS = 250L;
    /** Logged tracker errors per session. */
    static final int MAX_ERROR_LOGS = 5;
    /** Logged single-sample events per session. */
    static final int MAX_SINGLE_LOGS = 10;
    /**
     * Longest time after register during which off-wrist single samples are taken as the optical
     * front-end warming up and not delivered. On the SM-R920 the first PPG_GREEN sample of a
     * session carries a non-normal status and the mid-scale value 2^21; SHM decides "stable" from
     * the first single event only, so delivering it skips every window. SHM's watchdog first runs
     * 5 s after register; after this limit samples are delivered as they are (a watch that is
     * really off the wrist still reports wrist = false).
     */
    static final long WARMUP_MAX_MS = 3000L;

    static final String PKG = "com.samsung.android.hardware.sensormanager.";
    static final String EVENT_TYPE_CLASS = PKG + "SemPpgIhrnSensorParam$EventType";
    static final String ACCURACY_CLASS = PKG + "SemPpgIhrnSensorParam$Accuracy";

    /** Monotonic clock for the pairing timeout; replaced by the tests. */
    interface Clock {
        long now();
    }

    static volatile Clock clock = new Clock() {
        @Override public long now() { return SystemClock.elapsedRealtime(); }
    };
    // Test hooks: the periodic task intervals (the defaults above in production).
    static volatile long flushMs = FLUSH_MS;
    static volatile long tickMs = TICK_MS;

    private static final Object LOCK = new Object();
    private static volatile Session current;
    private static final AtomicBoolean hiddenApiCauseLogged = new AtomicBoolean(false);
    private static final AtomicBoolean enumsMissingLogged = new AtomicBoolean(false);
    private static volatile Object eventNormal;
    private static volatile Object accuracyNormal;
    private static volatile boolean enumsResolved;

    private IhrnAdapter() {}

    // ------------------------------------------------------------ entry points (from smali)

    /** sa2.c(listener, 0x25): start a session in single-sample mode. Always reports success. */
    public static boolean register(SemSensorListener listener, int type) {
        try {
            log("register: listener=" + listener + " type=" + type);
            Context ctx = appContext();
            Session old;
            Session s = new Session(listener);
            synchronized (LOCK) {
                old = current;
                current = s;
            }
            if (old != null) old.close();
            s.start(ctx);
        } catch (Throwable t) {
            log("register failed: " + t);
        }
        return true;
    }

    /**
     * sa2.d(listener, 0x25, attribute): the batch-mode attribute switches the event size
     * (ONE = 1 sample, MAX = 250). Always returns true.
     */
    public static boolean setAttribute(SemSensorListener listener, int type, SemSensorAttribute attribute) {
        try {
            Session s = current;
            Bundle b = attribute == null ? null : attribute.getAttribute(TYPE);
            if (s == null || b == null) {
                log("setAttribute: " + (s == null ? "no session" : "no attribute bundle") + "; ignored");
                return true;
            }
            Set<String> cmds = names(b.getSerializable("cmd"));
            if (cmds.contains("BATCH_MODE")) {
                Object mode = b.getSerializable("batch_mode");
                String name = mode instanceof Enum ? ((Enum<?>) mode).name() : String.valueOf(mode);
                if ("MAX".equals(name)) {
                    s.setBatchMode(true);
                } else if ("ONE".equals(name)) {
                    s.setBatchMode(false);
                } else {
                    log("setAttribute: batch mode " + name + " is not supported; ignored");
                }
            }
            if (cmds.isEmpty()) log("setAttribute: no command in the bundle; ignored");
        } catch (Throwable t) {
            log("setAttribute failed: " + t);
        }
        return true;
    }

    /** sa2.e(listener, 0x25) through WatchOverrides.unregisterListener: stop the session. */
    public static void unregister(SemSensorListener listener, int type) {
        try {
            Session s;
            synchronized (LOCK) {
                s = current;
                if (s == null) return;
                if (listener != null && s.listener != null && s.listener != listener) {
                    log("unregister: listener is not the registered one; ignored");
                    return;
                }
                current = null;
            }
            log("unregister: listener=" + listener);
            s.close();
        } catch (Throwable t) {
            log("unregister failed: " + t);
        }
    }

    // --------------------------------------------------------------------- session

    private static final class Session {
        final SemSensorListener listener;
        final ScheduledExecutorService exec;
        final long createdMs = clock.now();
        volatile GreenAccelTracker tracker;
        volatile boolean batchMode;
        final AtomicBoolean firstEventLogged = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);
        volatile int errorLogs;

        // Touched only on the delivery thread.
        final IhrnPairer pairer = new IhrnPairer();
        final IhrnConverter converter = new IhrnConverter();
        final IhrnWindowLog windowLog = new IhrnWindowLog();
        final List<IhrnSample> pending = new ArrayList<IhrnSample>(BATCH);
        boolean pendingForBatch;
        long lastGreenTs = Long.MIN_VALUE;
        int dropped;
        int nextSeq;
        int singleLogs;
        boolean settled;
        int warmupSkipped;

        Session(SemSensorListener listener) {
            this.listener = listener;
            this.exec = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "IhrnAdapter-deliver");
                    t.setDaemon(true);
                    return t;
                }
            });
        }

        boolean live() {
            return current == this && !closed.get();
        }

        void start(Context ctx) {
            exec.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() { safe(new Runnable() {
                    @Override public void run() { process(); }
                }); }
            }, tickMs, tickMs, TimeUnit.MILLISECONDS);
            exec.scheduleWithFixedDelay(new Runnable() {
                @Override public void run() {
                    GreenAccelTracker t = tracker;
                    if (t != null && live()) {
                        try { t.flush(); } catch (Throwable ignored) {}
                    }
                }
            }, flushMs, flushMs, TimeUnit.MILLISECONDS);
            if (ctx == null) {
                log("no application context; the Health Tracking trackers cannot start");
                return;
            }
            try {
                GreenAccelTracker t = new GreenAccelTracker(ctx, new Conn(this));
                tracker = t;
                t.start();
            } catch (Throwable t) {
                log("tracker start failed: " + t);
                tracker = null;
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            GreenAccelTracker t = tracker;
            tracker = null;
            exec.shutdownNow();
            if (t != null) {
                try { t.stop(); } catch (Throwable ignored) {}
            }
        }

        void setBatchMode(boolean batch) {
            if (batchMode != batch) log("batch mode -> " + (batch ? "MAX (250)" : "ONE (1)"));
            batchMode = batch;
        }

        /** Queue [task] on the delivery thread; a stopped session drops it. */
        void post(final Runnable task) {
            try {
                exec.execute(new Runnable() {
                    @Override public void run() { safe(task); }
                });
            } catch (RejectedExecutionException stopped) {
                // closed meanwhile
            }
        }

        void safe(Runnable task) {
            if (!live()) return;
            try {
                task.run();
            } catch (Throwable t) {
                log("processing failed: " + t);
            }
        }

        // ---- delivery thread from here

        void addGreen(PpgBatch batch) {
            long now = clock.now();
            for (PpgSample s : batch.samples) {
                if (s.timestampMs <= lastGreenTs) {
                    dropped++; // duplicate or out-of-order hub sample: never reused, never reordered
                    continue;
                }
                lastGreenTs = s.timestampMs;
                pairer.addGreen(s.timestampMs, s.greenAdc, s.greenStatus, now);
            }
            process();
        }

        void addAccel(AccelBatch batch) {
            for (AccelSample s : batch.samples) {
                pairer.addAccel(s.timestampMs, s.x, s.y, s.z);
            }
            process();
        }

        void process() {
            for (PairedSample ps : pairer.drain(clock.now())) {
                if (!live()) return;
                IhrnSample s = converter.convert(ps);
                if (!emit(s)) continue;
                String line = windowLog.add(s);
                if (line != null) log(line + (dropped > 0 ? " droppedOutOfOrder=" + dropped : ""));
            }
        }

        /** Queues or delivers [s]; false when it is a warm-up sample that is not delivered. */
        boolean emit(IhrnSample s) {
            boolean batch = batchMode;
            if (!settled) {
                if (!batch && !s.wrist && clock.now() - createdMs < WARMUP_MAX_MS) {
                    warmupSkipped++;
                    return false;
                }
                settled = true;
                if (warmupSkipped > 0) log("warm-up: " + warmupSkipped + " off-wrist sample(s) not delivered");
            }
            if (batch != pendingForBatch) { // mode switched: a half-filled batch is meaningless
                pending.clear();
                pendingForBatch = batch;
            }
            pending.add(s);
            if (!batch) {
                // SHM's stability check reads only these flags of the single-sample events
                // (stable = wrist && !motion), so they are logged until batch mode starts.
                if (singleLogs++ < MAX_SINGLE_LOGS) {
                    log("single: ts=" + s.tsMs + " green=" + s.ledGreen + " wrist=" + s.wrist
                            + " motion=" + s.motion + " agc=" + s.agc);
                }
                deliver(pending, -1);
                pending.clear();
            } else if (pending.size() == BATCH) {
                deliver(pending, nextSeq++);
                pending.clear();
            }
            return true;
        }

        void deliver(List<IhrnSample> samples, int sequence) {
            SemSensorListener l = listener;
            if (l == null || !live()) return;
            Bundle b = eventBundle(samples, sequence);
            if (b == null) return;
            try {
                SemPpgIhrnSensorEvent ev = new SemPpgIhrnSensorEvent(TYPE, b);
                if (!live()) return; // unregistered while the event was built
                l.onEventChanged(ev);
                if (firstEventLogged.compareAndSet(false, true)) {
                    log("first event " + (clock.now() - createdMs) + " ms after register (count "
                            + samples.size() + ")");
                }
            } catch (Throwable t) {
                if (t instanceof LinkageError && hiddenApiCauseLogged.compareAndSet(false, true)) {
                    log("deliver failed: hidden-API enforcement on One UI 8 Watch denies the"
                            + " SemPpgIhrnSensorEvent constructor to this app (" + t + ")");
                } else {
                    log("deliver failed: " + t);
                }
            }
        }
    }

    // --------------------------------------------------------------- tracker events

    private static final class Conn implements GreenAccelTracker.Listener {
        private final Session session;

        Conn(Session session) {
            this.session = session;
        }

        @Override public void onArmed() {
            log("trackers armed (PPG_GREEN, ACCELEROMETER) " + (clock.now() - session.createdMs)
                    + " ms after register");
        }

        @Override public void onGreen(final PpgBatch batch) {
            if (batch == null || batch.samples.length == 0 || !session.live()) return;
            session.post(new Runnable() {
                @Override public void run() { session.addGreen(batch); }
            });
        }

        @Override public void onAccel(final AccelBatch batch) {
            if (batch == null || batch.samples.length == 0 || !session.live()) return;
            session.post(new Runnable() {
                @Override public void run() { session.addAccel(batch); }
            });
        }

        @Override public void onError(String tracker, HealthError error) {
            if (session.errorLogs++ < MAX_ERROR_LOGS) log("onError " + tracker + ": " + error);
        }

        @Override public void onUnsupported(String tracker) {
            log(tracker + " not supported by the health service");
        }

        @Override public void onConnectionFailed(String reason) {
            log("onConnectionFailed " + reason);
        }

        @Override public void onConnectionEnded() {
            log("onConnectionEnded");
        }
    }

    // ---------------------------------------------------------------------- events

    /** The Bundle of one SemPpgIhrnSensorEvent (keys: SemPpgIhrnSensorEvent getters in SHM 1.5.1.022). */
    static Bundle eventBundle(List<IhrnSample> samples, int sequence) {
        resolveEnums();
        Object type = eventNormal;
        if (type == null) {
            if (enumsMissingLogged.compareAndSet(false, true)) {
                log("SemPpgIhrnSensorParam.EventType.NORMAL unavailable; no event can be delivered");
            }
            return null;
        }
        int n = samples.size();
        long[] systick = new long[n];
        long[] green = new long[n];
        int[] ax = new int[n], ay = new int[n], az = new int[n];
        boolean[] agc = new boolean[n], wrist = new boolean[n], motion = new boolean[n];
        boolean[] ecgMode = new boolean[n];
        int[] mode = new int[n];
        for (int i = 0; i < n; i++) {
            IhrnSample s = samples.get(i);
            systick[i] = s.tsMs;
            green[i] = s.ledGreen;
            ax[i] = s.accX;
            ay[i] = s.accY;
            az[i] = s.accZ;
            agc[i] = s.agc;
            wrist[i] = s.wrist;
            motion[i] = s.motion;
        }
        Bundle b = new Bundle();
        b.putSerializable("event_type", (java.io.Serializable) type);
        b.putInt("count", n);
        b.putLong("timestamp", systick[0]);
        b.putLong("systick", systick[0]);
        b.putLongArray("systick_array", systick);
        b.putLongArray("led_green", green);
        b.putIntArray("acc_x", ax);
        b.putIntArray("acc_y", ay);
        b.putIntArray("acc_z", az);
        b.putBooleanArray("agc", agc);
        b.putBooleanArray("ecg_mode", ecgMode);
        b.putBooleanArray("wrist", wrist);
        b.putBooleanArray("motion", motion);
        b.putIntArray("mode", mode);
        b.putInt("data_sequence", sequence);
        Object acc = accuracyNormal;
        if (acc != null) b.putSerializable("accuracy", (java.io.Serializable) acc);
        return b;
    }

    private static void resolveEnums() {
        if (enumsResolved) return;
        eventNormal = enumConstant(EVENT_TYPE_CLASS, "NORMAL");
        accuracyNormal = enumConstant(ACCURACY_CLASS, "NORMAL");
        enumsResolved = eventNormal != null; // retry later while the event type is missing
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

    static Set<String> names(Object cmd) {
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

    // ----------------------------------------------------------------------- utils

    /** Package-visible for the tests: the tracker callbacks of the current session, or null. */
    static GreenAccelTracker.Listener trackerListener() {
        Session s = current;
        return s == null ? null : new Conn(s);
    }

    /** Blocks until the delivery thread has processed everything queued so far (tests). */
    static void settle() throws Exception {
        Session s = current;
        if (s == null) return;
        s.exec.submit(new Runnable() { @Override public void run() { } }).get(5, TimeUnit.SECONDS);
    }

    /** Runs the pairing-timeout check on the delivery thread now (tests). */
    static void tickNow() throws Exception {
        final Session s = current;
        if (s == null) return;
        s.exec.submit(new Runnable() { @Override public void run() { s.safe(new Runnable() {
            @Override public void run() { s.process(); }
        }); } }).get(5, TimeUnit.SECONDS);
    }

    static boolean isActive() {
        return current != null;
    }

    static boolean isBatchMode() {
        Session s = current;
        return s != null && s.batchMode;
    }

    /** Back to the idle state (tests). */
    static void resetForTest() {
        Session s;
        synchronized (LOCK) {
            s = current;
            current = null;
        }
        if (s != null) s.close();
        hiddenApiCauseLogged.set(false);
        enumsMissingLogged.set(false);
        flushMs = FLUSH_MS;
        tickMs = TICK_MS;
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
