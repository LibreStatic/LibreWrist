package shminterop.bp;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import com.samsung.android.hardware.sensormanager.SemBpManualSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorListener;

import shminterop.hsdk.HealthError;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;
import shminterop.hsdk.PpgTracker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge Health Tracking Service (shminterop.hsdk client) -> blood pressure pipeline of the
 * modified SHM.
 *
 * Replaces the registration of sensor 28 (0x1c, manual BP), which the sem_sensor binder rejects
 * for SSENSOR: it obtains the PPG_ON_DEMAND green tracker from the health service (normal
 * permissions) and repackages every sample as a SemBpManualSensorEvent, the event type the
 * original consumer (pl.smali, BP case of onEventChanged) expects:
 *
 *   Bundle key "green_raw" = raw green-LED ADC count, passed through unchanged.
 *
 * Unlike ECG there is no scale or centre in the APK (pl copies the int to the float ring as is;
 * the interpretation is done by the native BP library) and no sequence requirement (the BP case
 * only casts the event and reads green_raw). The consumer expects one sample per event at about
 * 100 Hz: the pipeline watchdog (rl.smali) needs more than 200 accumulated samples before it
 * starts feeding BpAlgorithm, and the pump drains one sample per 10 ms tick.
 *
 * While the service connects (or if it never connects) a keepalive emits synthetic events with
 * green_raw = 0 at 10 Hz so the pipeline is not starved; they stop as soon as real data flows.
 * Sensor 24 (HR) is deliberately not intercepted: its events only carry motion/quality flags for
 * the abort logic, never data, and the native library derives the result HR from the PPG.
 */
public final class BpAdapter {

    static final int TYPE_BP = 28;
    static final String GREEN_RAW_KEY = "green_raw";
    static final String TAG = "BpAdapter";

    static volatile SemSensorListener sink;
    static PpgTracker tracker;
    // PPG callbacks arrive on several binder threads; the lock keeps ring writes and stats coherent.
    static final Object DELIVERY_LOCK = new Object();
    static final AtomicBoolean running = new AtomicBoolean(false);
    static final AtomicBoolean everReal = new AtomicBoolean(false);
    static final AtomicBoolean hiddenApiCauseLogged = new AtomicBoolean(false);
    static volatile long lastRealMs;
    static ScheduledExecutorService keepalive;

    // Touched only under DELIVERY_LOCK. statN counts samples fed to the pipeline, statBad the
    // ones whose green channel the service did not report as normal (they are fed anyway, like
    // the vendor HAL path does; only the ratio is logged).
    static int statBatches = 0;
    static int statN = 0, statBad = 0;
    static int statMin = Integer.MAX_VALUE, statMax = Integer.MIN_VALUE;
    static long statSum = 0;

    private BpAdapter() {}

    /**
     * Called from the patched registerListener of sensor 28 in sl.g (common to measurement and
     * calibration). The original int result is discarded there, so unlike {@code EcgAdapter.start}
     * there is nothing to return.
     */
    public static void start(SemSensorListener listener, int sensorType) {
        if (sensorType != TYPE_BP) {
            log("start: ignoring sensor type " + sensorType);
            return;
        }
        sink = listener;
        stopTracker();
        running.set(true);
        everReal.set(false);
        synchronized (DELIVERY_LOCK) { resetStats(); }
        Context ctx = appContext();
        log("start: sink=" + listener + " ctx=" + ctx);
        if (ctx == null) {
            startKeepalive();
            return;
        }
        try {
            tracker = new PpgTracker(ctx, new Conn());
            tracker.start();
        } catch (Throwable t) {
            log("connect failed: " + t);
            tracker = null;
        }
        startKeepalive();
    }

    /** Called from WatchOverrides.unregisterListener for sensor 28 (sl.i). */
    public static void stop(SemSensorListener listener, int sensorType) {
        if (sensorType != TYPE_BP) return;
        log("stop");
        running.set(false);
        stopKeepalive();
        stopTracker();
        sink = null;
    }

    static void stopTracker() {
        PpgTracker t = tracker;
        tracker = null;
        if (t != null) {
            try { t.stop(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- service events

    static final class Conn implements PpgTracker.Listener {
        @Override public void onArmed() {
            log("tracker armed");
        }

        @Override public void onUnsupported() {
            log("PPG_ON_DEMAND not supported by the health service");
        }

        @Override public void onConnectionFailed(String reason) {
            log("onConnectionFailed " + reason);
        }

        @Override public void onConnectionEnded() {
            log("onConnectionEnded");
        }

        @Override public void onError(HealthError error) {
            log("onError " + error);
        }

        @Override public void onBatch(PpgBatch batch) {
            if (!running.get() || sink == null || batch == null || batch.samples.length == 0) return;
            synchronized (DELIVERY_LOCK) { deliverBatch(batch); }
        }

        private void deliverBatch(PpgBatch batch) {
            lastRealMs = SystemClock.elapsedRealtime();
            everReal.set(true);
            PpgSample[] points = batch.samples;
            int n = points.length;
            for (int i = 0; i < n; i++) {
                int adc = points[i].greenAdc;
                if (!points[i].isGreenNormal()) statBad++;
                statN++;
                if (adc < statMin) statMin = adc;
                if (adc > statMax) statMax = adc;
                statSum += adc;
                deliver(adc);
            }
            statBatches++;
            if (statBatches % 100 == 0 && statN > 0) {
                log(String.format("stats: n=%d min=%d max=%d mean=%.1f adc abnormal=%d/%d"
                                + " (last real %d ms ago)",
                        statN, statMin, statMax, (double) statSum / statN, statBad, statN,
                        lastRealMs == 0L ? -1L : SystemClock.elapsedRealtime() - lastRealMs));
                resetStats();
            }
        }
    }

    /** Emits one event per sample: the BP case of pl reads exactly one green_raw per event. */
    static void deliver(int greenAdc) {
        SemSensorListener l = sink;
        if (l == null || !running.get()) return;
        try {
            Bundle b = new Bundle();
            b.putInt(GREEN_RAW_KEY, greenAdc);
            // The other keys of the vendor event (agc_*, acc_*, accuracy) are never read by the
            // patched SHM (only getGreenRaw is called) and Bundle getters default to 0 anyway.
            SemBpManualSensorEvent ev = new SemBpManualSensorEvent(TYPE_BP, b);
            l.onEventChanged(ev);
        } catch (Throwable t) {
            if (t instanceof LinkageError && hiddenApiCauseLogged.compareAndSet(false, true)) {
                log("deliver failed: hidden-API enforcement on One UI 8 Watch denies the"
                        + " SemBpManualSensorEvent constructor to this app (" + t + ")");
            } else {
                log("deliver failed: " + t);
            }
        }
    }

    // --------------------------------------------------------------- keepalive

    static void startKeepalive() {
        stopKeepalive();
        keepalive = Executors.newSingleThreadScheduledExecutor();
        keepalive.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                try {
                    if (!running.get()) return;
                    if (everReal.get()) return; // real data already flowing: do not synthesise
                    synchronized (DELIVERY_LOCK) {
                        if (everReal.get()) return;
                        deliver(0); // flatline: keeps the pipeline fed, no pulse to misread
                    }
                } catch (Throwable ignored) {}
            }
        }, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    static void stopKeepalive() {
        ScheduledExecutorService k = keepalive;
        keepalive = null;
        if (k != null) {
            try { k.shutdownNow(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ utils

    static void resetStats() {
        statBatches = 0;
        statN = 0;
        statBad = 0;
        statMin = Integer.MAX_VALUE;
        statMax = Integer.MIN_VALUE;
        statSum = 0;
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
        android.util.Log.i(TAG, s);
    }
}
