package shminterop.spo2;

import android.content.Context;
import android.os.Bundle;
import android.os.PowerManager;
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
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TimeZone;
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
     * This is only the default: each night checks the orientation (see ORIENTATION_*) and the
     * choice is persisted.
     */
    static final boolean SWAP_RED_IR = true;

    /** SHM's V1 evaluation needs this share of the window with a value (for the coverage log). */
    static final double SHM_MIN_COVERAGE_PCT = 70.0;

    // Red/IR orientation check: median R over the first finite seconds of a recording. A wrong
    // orientation gives about 1/R_true (1.75 versus 0.52 on the SM-R920); 1.2 would be about
    // 82 % SpO2 sustained at rest, which is not physiological.
    static final int ORIENTATION_SAMPLES = 120;
    static final double ORIENTATION_FLIP_R = 1.2;

    static final String SIM_PROP = "debug.shminterop.spo2_sim";
    static final String PKG = "com.samsung.android.hardware.sensormanager.";
    static final String DATA_TYPE_CLASS = PKG + "SemSleepWatchSpO2SensorParam$DataType";

    // Version reported for both algorithm kinds (only the "a.b.c" shape and versionMap key 1 matter).
    static final int[] VERSION_CALCULATION = {1, 11, 1};
    static final int[] VERSION_SIGNALCHECK = {1, 0, 0};

    /**
     * A SET_OPERATION_MODE while the newest stored second is younger than this resumes the night
     * instead of clearing it. GET_DATA only returns seconds inside the requested window, so keeping
     * older seconds never pollutes an answer; clearing only bounds the file, and a new night is
     * always more than 6 h after the last recorded second of the previous one. A brief awakening
     * (AWAKE, GET_DATA, unregister, then SLEEPING again) therefore keeps the earlier part.
     */
    static final long RESUME_MS = 6L * 60L * 60L * 1000L;
    /** After a process restart, recording resumes by itself if the marker exists and the newest second is this fresh. */
    static final long AUTO_RESUME_MS = 30L * 60L * 1000L;
    /** If SHM skips SET_OPERATION_MODE, a listener silent for this long after register starts recording. */
    static final long FALLBACK_START_MS = 5000L;
    /**
     * The wake lock is held briefly, like SHM's own PowerLock for its sensor batches (1 s per
     * batch), instead of for the whole night: per PPG batch, and while the tracker (re)connects.
     */
    static final long BATCH_WAKE_LOCK_MS = 1000L;
    static final long ARM_WAKE_LOCK_MS = 10000L;
    static final long LIVENESS_MS = 15000L;
    static final long WATCHDOG_TICK_MS = 5000L;
    static final long FLUSH_MS = 60000L;

    private static final Object LOCK = new Object();
    private static final Object FEED_LOCK = new Object();

    private static final Set<SemSensorListener> listeners =
            Collections.newSetFromMap(new IdentityHashMap<SemSensorListener, Boolean>());
    static final Spo2Store store = new Spo2Store();
    /** Bounded journal of the overnight events, dumped to logcat on GET_DATA (docs/spo2-adapter.md). */
    static final NightLog journal = new NightLog();
    static final long AGGREGATE_MS = 15L * 60L * 1000L;
    /** The dump covers journal entries from this long before the requested window. */
    static final long DUMP_LEAD_MS = 2L * 60L * 60L * 1000L;
    private static boolean journalAttached;
    private static boolean processStartNoted;
    private static long lastAggregateMs;
    private static int aggSeconds, aggValid; // under FEED_LOCK
    /** Test hook: every log line is also appended here when set. */
    static volatile List<String> logSink;

    private static SemSensorListener recorder;
    private static boolean recorderAdopted; // recorder role taken at register, not by SET_OPERATION_MODE
    // Listeners that sent SET_OPERATION_MODE, GET_VERSION or GET_DATA since they registered.
    private static final Set<SemSensorListener> commanded =
            Collections.newSetFromMap(new IdentityHashMap<SemSensorListener, Boolean>());
    private static boolean autoResumeChecked;
    private static File markerFile;
    private static File orientationFile;
    private static PowerManager.WakeLock wakeLock;
    private static ScheduledExecutorService fallback;
    /** Test hooks: the application context, and the delay of the fallback start check. */
    static volatile Context contextOverride;
    static volatile long fallbackDelayMs = FALLBACK_START_MS;
    /** Coverage text of the last GET_DATA answer (log and tests). */
    static volatile String lastCoverage = "";
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

    private static boolean swap = SWAP_RED_IR;
    private static boolean orientDecided;
    private static final List<Double> orientR = new ArrayList<Double>();

    private static final Spo2Estimator.Output OUT = new Spo2Estimator.Output() {
        @Override public void onSecond(long secondEpochMs, int value, double r) {
            store.put(secondEpochMs, value);
            statSeconds++;
            if (value > 0) {
                statValid++;
                statR.add(r);
            }
            if (!orientDecided && !Double.isNaN(r) && !Double.isInfinite(r)) {
                orientR.add(r);
                if (orientR.size() >= ORIENTATION_SAMPLES) decideOrientation();
            }
        }
    };

    static Spo2Estimator newEstimator() {
        return new Spo2Estimator(OUT);
    }

    /** Called under FEED_LOCK once enough finite R values were collected; decides once. */
    private static void decideOrientation() {
        orientDecided = true;
        List<Double> s = new ArrayList<Double>(orientR);
        orientR.clear();
        Collections.sort(s);
        double med = s.get(s.size() / 2);
        if (med > ORIENTATION_FLIP_R) {
            swap = !swap;
            estimator = newEstimator();
            log(String.format(Locale.ROOT,
                    "orientation: median R %.3f over %d s, swapping red/IR (swap=%b)",
                    med, ORIENTATION_SAMPLES, swap));
            note(String.format(Locale.ROOT, "orientation: median R %.3f, swapped (swap=%b)", med, swap));
            writeOrientation(swap);
        } else {
            log(String.format(Locale.ROOT, "orientation: median R %.3f, keeping swap=%b", med, swap));
            note(String.format(Locale.ROOT, "orientation: median R %.3f, kept (swap=%b)", med, swap));
        }
    }

    private static void writeOrientation(boolean value) {
        File f = orientationFile;
        if (f == null) return;
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(("swap=" + value + "\n").getBytes("UTF-8"));
            } finally {
                o.close();
            }
        } catch (Throwable t) {
            log("orientation not persisted: " + t);
            note("persistence failure: orientation not persisted: " + t);
        }
    }

    /** The persisted orientation, or SWAP_RED_IR when missing or unreadable. */
    private static boolean readOrientation(File f) {
        if (f == null || !f.isFile()) return SWAP_RED_IR;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line = r.readLine();
                if (line != null) {
                    line = line.trim();
                    if (line.equals("swap=true")) return true;
                    if (line.equals("swap=false")) return false;
                }
            } finally {
                r.close();
            }
        } catch (Throwable t) {
            log("orientation unreadable: " + t);
        }
        return SWAP_RED_IR;
    }

    private static final PpgTracker.Listener CONN = new Conn();

    private SpO2Adapter() {}

    // ------------------------------------------------------------ entry points (from smali)

    /** sa2.c(listener, type) for type 0x31: remember the listener, report success. */
    public static boolean register(SemSensorListener listener, int type) {
        try {
            ApneaTestDriver.ensureStarted();
            entered();
            log("register: listener=" + listener + " type=" + type);
            note("register " + idOf(listener));
            synchronized (LOCK) {
                maybeAutoResume();
                if (listener != null) {
                    listeners.add(listener);
                    commanded.remove(listener);
                    if (recording && recorder == null) {
                        recorder = listener;
                        recorderAdopted = true;
                        log("register: now tied to the auto-resumed recording: " + listener);
                    }
                }
            }
            if (listener != null) scheduleFallback(listener);
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
            entered();
            synchronized (LOCK) {
                maybeAutoResume();
            }
            Bundle b = attribute == null ? null : attribute.getAttribute(TYPE);
            if (b == null) {
                log("setAttribute: no attribute bundle for type " + type + "; ignored");
                return true;
            }
            Set<String> cmds = commandNames(b.getSerializable("cmd"));
            log("setAttribute: listener=" + listener + " cmds=" + cmds);
            if (listener != null && (cmds.contains("SET_OPERATION_MODE")
                    || cmds.contains("GET_VERSION") || cmds.contains("GET_DATA"))) {
                synchronized (LOCK) {
                    commanded.add(listener);
                    // A version probe is not the sleep start: do not keep the adopted recorder role.
                    if (cmds.contains("GET_VERSION") && listener == recorder && recorderAdopted) {
                        recorder = null;
                        recorderAdopted = false;
                    }
                }
            }
            if (cmds.contains("SET_OPERATION_MODE")) {
                log("SET_OPERATION_MODE mode=" + b.getSerializable("mode"));
                note("cmd SET_OPERATION_MODE mode=" + b.getSerializable("mode") + " " + idOf(listener));
                startRecording(listener);
            }
            if (cmds.contains("GET_VERSION")) {
                note("cmd GET_VERSION " + b.getSerializable("versionType"));
                sendVersion(listener, b.getSerializable("versionType"));
            }
            if (cmds.contains("GET_DATA")) {
                note("cmd GET_DATA window " + b.getLong("startTimestamp") + ".." + b.getLong("endTimestamp"));
                answerData(listener, b.getLong("startTimestamp"), b.getLong("endTimestamp"));
                stopOrphanRecording();
            }
            if (cmds.contains("FLUSH")) {
                log("FLUSH accepted (nothing to flush)");
                note("cmd FLUSH");
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
            entered();
            log("unregister: listener=" + listener + " type=" + type);
            boolean wasRecorder;
            synchronized (LOCK) {
                listeners.remove(listener);
                commanded.remove(listener);
                wasRecorder = listener != null && listener == recorder;
                if (wasRecorder) {
                    recorder = null;
                    recorderAdopted = false;
                }
            }
            note("unregister " + idOf(listener) + (wasRecorder ? " (was the recorder)" : ""));
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
        log("GET_DATA " + startMs + ".." + endMs + " -> " + events.size() + " event(s)"
                + (lastCoverage.isEmpty() ? "" : ", " + lastCoverage));
        deliver(listener, events);
        dumpNight(startMs, endMs);
    }

    /** Logs the night summary and the journal to logcat so a morning log export explains the night. */
    static void dumpNight(long startMs, long endMs) {
        try {
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
            TimeZone tz = TimeZone.getDefault();
            day.setTimeZone(tz);
            clock.setTimeZone(tz);
            long windowS = Math.max(0L, (endMs - startMs) / 1000L);
            String cov = lastCoverage.isEmpty()
                    ? String.format(Locale.ROOT, "coverage 0%% (SHM needs %.0f%%)", SHM_MIN_COVERAGE_PCT)
                    : lastCoverage;
            int stored = store.size();
            String span = stored == 0 ? "-"
                    : clock.format(new Date(store.oldest())) + ".." + clock.format(new Date(store.newest()));
            boolean held = false;
            PowerManager.WakeLock wl = wakeLock;
            if (wl != null) {
                try { held = wl.isHeld(); } catch (Throwable ignored) {}
            }
            log("night: window " + day.format(new Date(startMs)) + ".." + clock.format(new Date(endMs))
                    + " (" + windowS + " s), " + cov + ", recording=" + recording
                    + ", recorder=" + (recorder != null) + ", stored " + stored + " s ("
                    + store.validCount() + " valid) " + span + ", wake lock held=" + held);
            for (NightLog.Entry e : journal.since(startMs - DUMP_LEAD_MS)) {
                log("night-event: " + clock.format(new Date(e.epochMs)) + " " + e.text);
            }
        } catch (Throwable t) {
            log("night dump failed: " + t);
        }
    }

    /** The events answering GET_DATA: NORMAL chunks, or a single EMPTY event. */
    static List<Bundle> dataEvents(long startMs, long endMs) {
        List<Bundle> events = new ArrayList<Bundle>();
        int[] sim = SyntheticNight.parseSpec(SysProp.get(SIM_PROP));
        List<Spo2Answer.Chunk> chunks;
        lastCoverage = "";
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
            int total = 0, valid = 0;
            for (Spo2Answer.Chunk c : chunks) {
                total += c.size;
                for (int v : c.spo2) if (v != 0) valid++;
            }
            if (total > 0) {
                lastCoverage = String.format(Locale.ROOT, "coverage %.1f%% (SHM needs %.0f%%)",
                        100.0 * valid / total, SHM_MIN_COVERAGE_PCT);
            }
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
                            note("deliver failed (hidden API?): " + t);
                        } else {
                            log("deliver failed: " + t);
                            note("deliver failed: " + t);
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
            recorderAdopted = false;
            if (recording) {
                log("start: already recording; now tied to " + listener);
                return;
            }
            Context ctx = appContext();
            attachJournal(ctx);
            if (ctx != null) {
                try {
                    File dir = new File(ctx.getFilesDir(), "shminterop-spo2");
                    store.attach(new File(dir, "night.bin"));
                    markerFile = new File(dir, "recording");
                    orientationFile = new File(dir, "orientation");
                } catch (Throwable t) {
                    log("no persistence: " + t);
                }
            }
            if (store.size() == 0) store.load();
            long now = System.currentTimeMillis();
            if (store.size() > 0 && now - store.newest() <= RESUME_MS) {
                log("start: resuming the night (" + store.size() + " s stored, newest "
                        + (now - store.newest()) / 1000 + " s ago)");
                note("recording start: resumed with " + store.size() + " s stored");
            } else {
                store.clear();
                log("start: new night");
                note("recording start: new night");
            }
            synchronized (FEED_LOCK) {
                swap = readOrientation(orientationFile);
                orientDecided = false;
                orientR.clear();
                estimator = newEstimator();
                statSeconds = 0;
                statValid = 0;
                statR.clear();
                aggSeconds = 0;
                aggValid = 0;
                lastAggregateMs = now;
            }
            recording = true;
            writeMarker();
            acquireWakeLock(ctx);
            holdWakeLock(ARM_WAKE_LOCK_MS);
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
            note("recording stop: " + store.size() + " s stored");
            recording = false;
            stopTracker();
            ScheduledExecutorService w = watchdog;
            watchdog = null;
            if (w != null) w.shutdownNow();
            logStats(true);
            store.flush();
            deleteMarker();
            releaseWakeLock();
        }
    }

    private static void writeMarker() {
        File m = markerFile;
        if (m == null) return;
        try {
            File dir = m.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            new FileOutputStream(m).close();
        } catch (Throwable t) {
            log("recording marker not written: " + t);
            note("persistence failure: recording marker not written: " + t);
        }
    }

    private static void deleteMarker() {
        File m = markerFile;
        if (m != null && m.exists() && !m.delete()) {
            log("recording marker not deleted");
            note("recording marker not deleted");
        }
    }

    /**
     * Creates the recording's wake lock without holding it; holdWakeLock takes it for a short,
     * timed span (one PPG batch, or a tracker connection). stopRecording releases it.
     */
    private static void acquireWakeLock(Context ctx) {
        if (ctx == null) return;
        try {
            Object pm = ctx.getSystemService(Context.POWER_SERVICE);
            if (!(pm instanceof PowerManager)) {
                log("no PowerManager; recording without a wake lock");
                note("wake lock not acquired: no PowerManager");
                return;
            }
            releaseWakeLock();
            PowerManager.WakeLock wl =
                    ((PowerManager) pm).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shminterop:spo2");
            wl.setReferenceCounted(false);
            wakeLock = wl;
        } catch (Throwable t) {
            log("wake lock not acquired: " + t);
            note("wake lock not acquired: " + t);
        }
    }

    /** Holds the wake lock for [ms] from now (a new hold replaces the previous timeout). */
    static void holdWakeLock(long ms) {
        PowerManager.WakeLock wl = wakeLock;
        if (wl == null) return;
        try {
            wl.acquire(ms);
        } catch (Throwable t) {
            log("wake lock hold: " + t);
        }
    }

    private static void releaseWakeLock() {
        PowerManager.WakeLock wl = wakeLock;
        wakeLock = null;
        if (wl != null) {
            try { if (wl.isHeld()) wl.release(); } catch (Throwable t) { log("wake lock release: " + t); }
        }
    }

    /**
     * Once per process, on the first entry: if the previous process died while recording (marker
     * file) and the stored night is fresh, record again without a listener. Under LOCK.
     */
    private static void maybeAutoResume() {
        if (autoResumeChecked || recording) return;
        Context ctx = appContext();
        if (ctx == null) return;
        attachJournal(ctx);
        autoResumeChecked = true;
        try {
            File dir = new File(ctx.getFilesDir(), "shminterop-spo2");
            File marker = new File(dir, "recording");
            if (!marker.exists()) return;
            store.attach(new File(dir, "night.bin"));
            store.load();
            long now = System.currentTimeMillis();
            if (store.size() > 0 && now - store.newest() < AUTO_RESUME_MS) {
                log("start: auto-resuming after a process restart");
                note("recording start: auto-resume after restart (" + store.size() + " s stored)");
                startRecording(null);
            } else {
                log("stale recording marker removed");
                marker.delete();
            }
        } catch (Throwable t) {
            log("auto-resume failed: " + t);
        }
    }

    /**
     * GET_DATA marks the end of the night. A recording no listener owns (auto-resumed, not yet
     * adopted) would never see the unregister that normally stops it, so it stops here.
     */
    private static void stopOrphanRecording() {
        synchronized (LOCK) {
            if (!recording || recorder != null) return;
            log("GET_DATA with no recording listener; stopping the auto-resumed recording");
            stopRecording();
        }
    }

    /** One-shot check FALLBACK_START_MS after register, for SHM paths that skip SET_OPERATION_MODE. */
    private static synchronized void scheduleFallback(final SemSensorListener listener) {
        if (fallback == null) {
            fallback = Executors.newSingleThreadScheduledExecutor(daemons("SpO2Adapter-fallback"));
        }
        fallback.schedule(new Runnable() {
            @Override public void run() {
                try { fallbackCheck(listener); } catch (Throwable t) { log("fallback: " + t); }
            }
        }, fallbackDelayMs, TimeUnit.MILLISECONDS);
    }

    /** Package-visible for tests: the body of the fallback timer. */
    static void fallbackCheck(SemSensorListener listener) {
        synchronized (LOCK) {
            if (recording || !listeners.contains(listener) || commanded.contains(listener)) return;
            log("start: no SET_OPERATION_MODE " + fallbackDelayMs / 1000
                    + " s after register; recording anyway");
            note("recording start: fallback start without SET_OPERATION_MODE " + idOf(listener));
            startRecording(listener);
        }
    }

    private static void startTracker(Context ctx) {
        if (ctx == null) {
            log("no application context; the PPG tracker cannot start");
            note("no application context; the PPG tracker cannot start");
            return;
        }
        try {
            tracker = new PpgTracker(ctx,
                    Arrays.asList(ServiceProtocol.PPG_TYPE_RED, ServiceProtocol.PPG_TYPE_IR), CONN);
            tracker.start();
        } catch (Throwable t) {
            log("tracker start failed: " + t);
            note("tracker start failed: " + t);
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
                note("watchdog: no PPG batch for " + (e - ref) / 1000 + " s, re-arming the tracker");
                stopTracker();
                lastArmMs = e;
                holdWakeLock(ARM_WAKE_LOCK_MS);
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
        logStats(false);
    }

    private static void logStats(boolean force) {
        synchronized (FEED_LOCK) {
            double med = Double.NaN;
            if (!statR.isEmpty()) {
                List<Double> s = new ArrayList<Double>(statR);
                Collections.sort(s);
                med = s.get(s.size() / 2);
            }
            log(String.format("stats: valid %d/%d s, median R %.3f (swap=%b), stored %d s",
                    statValid, statSeconds, med, swap, store.size()));
            aggSeconds += statSeconds;
            aggValid += statValid;
            statSeconds = 0;
            statValid = 0;
            statR.clear();
            long now = System.currentTimeMillis();
            if (force ? aggSeconds > 0 : now - lastAggregateMs >= AGGREGATE_MS) {
                note("period: valid " + aggValid + "/" + aggSeconds + " s, stored " + store.size() + " s");
                aggSeconds = 0;
                aggValid = 0;
                lastAggregateMs = now;
            }
        }
    }

    /** Feeds one service sample to the estimator, applying the channel orientation. */
    static void feed(Spo2Estimator est, PpgSample s) {
        boolean sw = swap;
        int red = sw ? s.irAdc : s.redAdc;
        int ir = sw ? s.redAdc : s.irAdc;
        est.add(s.timestampMs, red, ir, s.isRedNormal() && s.isIrNormal());
    }

    static final class Conn implements PpgTracker.Listener {
        @Override public void onArmed() { log("tracker armed (RED, IR)"); note("tracker armed"); }

        @Override public void onUnsupported() {
            log("PPG_ON_DEMAND not supported by the health service");
            note("tracker onUnsupported: PPG_ON_DEMAND not supported");
        }

        @Override public void onConnectionFailed(String reason) {
            log("onConnectionFailed " + reason);
            note("tracker onConnectionFailed " + reason);
        }

        @Override public void onConnectionEnded() { log("onConnectionEnded"); note("tracker onConnectionEnded"); }

        @Override public void onError(HealthError error) { log("onError " + error); note("tracker onError " + error); }

        @Override public void onBatch(PpgBatch batch) {
            if (!recording || batch == null) return;
            holdWakeLock(BATCH_WAKE_LOCK_MS);
            lastBatchMs = SystemClock.elapsedRealtime();
            synchronized (FEED_LOCK) {
                // Re-read per sample: an orientation flip replaces the estimator mid-batch.
                for (PpgSample s : batch.samples) {
                    Spo2Estimator est = estimator;
                    if (est == null) return;
                    feed(est, s);
                }
            }
        }
    }

    // ------------------------------------------------------------------------ utils

    /** Package-visible for the tests: the tracker callbacks the adapter registered. */
    static PpgTracker.Listener trackerListener() {
        return CONN;
    }

    static boolean currentSwap() {
        synchronized (FEED_LOCK) {
            return swap;
        }
    }

    static boolean isRecording() {
        return recording;
    }

    /** Back to the idle state (tests). */
    static void resetForTest() {
        synchronized (LOCK) {
            recording = false;
            recorder = null;
            recorderAdopted = false;
            listeners.clear();
            commanded.clear();
            autoResumeChecked = false;
            journalAttached = false;
            processStartNoted = false;
            journal.attach(null);
            journal.clear();
            markerFile = null;
            orientationFile = null;
            releaseWakeLock();
            stopTracker();
            ScheduledExecutorService w = watchdog;
            watchdog = null;
            if (w != null) w.shutdownNow();
        }
        synchronized (FEED_LOCK) {
            estimator = null;
            swap = SWAP_RED_IR;
            orientDecided = false;
            orientR.clear();
            statSeconds = 0;
            statValid = 0;
            statR.clear();
            aggSeconds = 0;
            aggValid = 0;
        }
        store.attach(null);
        store.clear();
    }

    static Context appContext() {
        Context o = contextOverride;
        if (o != null) return o;
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
        List<String> sink = logSink;
        if (sink != null) sink.add(s);
    }

    // ------------------------------------------------------------------ night journal

    /** Records one journal entry; never throws. */
    static void note(String text) {
        try {
            journal.add(System.currentTimeMillis(), text);
        } catch (Throwable ignored) {
            // diagnostics must never break recording
        }
    }

    private static String idOf(Object listener) {
        return listener == null ? "null" : "#" + Integer.toHexString(System.identityHashCode(listener));
    }

    /** First entry into the adapter in this process: journal it and attach the file when possible. */
    private static void entered() {
        try {
            boolean first;
            synchronized (journal) {
                first = !processStartNoted;
                processStartNoted = true;
            }
            if (first) note("process start");
            attachJournal(appContext());
        } catch (Throwable ignored) {
        }
    }

    /**
     * The journal lives in the app's external files dir (Android/data/<package>/files), which
     * the adb shell user can read, so the patcher's log capture reads it without waking or
     * changing this app. Without shared storage it stays in the internal files dir.
     */
    static File journalFile(Context ctx) {
        File base = null;
        try {
            base = ctx.getExternalFilesDir(null);
        } catch (Throwable ignored) {
            // no shared storage
        }
        if (base == null) base = ctx.getFilesDir();
        return new File(new File(base, "shminterop-spo2"), "events.log");
    }

    /** Attaches the journal file once per process; earlier entries are written on attach. */
    private static void attachJournal(Context ctx) {
        if (ctx == null) return;
        try {
            synchronized (journal) {
                if (journalAttached) return;
                journalAttached = true;
            }
            journal.attach(journalFile(ctx));
        } catch (Throwable t) {
            log("night journal not attached: " + t);
        }
    }
}
