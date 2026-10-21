package shminterop.ecg;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import com.samsung.android.hardware.sensormanager.SemEcgSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorListener;

import shminterop.hsdk.EcgBatch;
import shminterop.hsdk.EcgSample;
import shminterop.hsdk.EcgTracker;
import shminterop.hsdk.HealthError;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bridge Health Tracking Service (shminterop.hsdk client) -> SemSensor pipeline of the modified SHM.
 *
 * Replaces the registration of sensor 27 (ECG), which the sem_sensor binder rejects
 * for SSENSOR: it obtains the ECG tracker from the health service (normal
 * permissions) and repackages every batch as a SemEcgSensorEvent with the raw
 * int[27] the original parser (pl.smali) expects:
 *
 *   [4..13] ADC samples: 0x200000 + mV / 2.6614e-4
 *   [14]    lead status in bits 16..23 (0 = finger, 5 = no contact)
 *   [15]    bits 8..15 = sample count (10), bits 0..7 = sequence (uint8)
 *   [25/26] electrode offsets (0x200000 -> 0 mV)
 *
 * Emits batches of 10 samples. While the service connects (or if it never
 * connects) a keepalive emits synthetic events with lead=5 at 10 Hz to feed the
 * pipeline 1 s watchdog; the synthetic events stop as soon as real data flows.
 *
 */
public final class EcgAdapter {

    static final int TYPE_ECG = 27;
    static final int BATCH = 10;
    static final int LEN = 27;
    static final int ADC_CENTER = 0x200000;
    static final double LSB_MV = 2.6614e-4;
    static final String TAG = "EcgAdapter";

    // Electrode baseline: pl reads it ONCE from the first event ([25]/[26]) and uses it
    // as the signal validity range. With a fixed offset at the centre, every real
    // signal ends up "out of range" -> 'Invalid Signal Value Range'. So the first real
    // batch is held back, its mean is computed and that is the baseline.
    static volatile boolean offsetsSet = false;
    static final java.util.List<Float> baselineBuf = new java.util.ArrayList<Float>();
    static volatile int bandHiAdc = ADC_CENTER + (int) Math.round(5.0 / LSB_MV); // +5mV
    static volatile int bandLoAdc = ADC_CENTER - (int) Math.round(5.0 / LSB_MV); // -5mV
    // Baseline EMA: the SHS ECG_MV is DC-coupled (electrode baseline, -142..-31 mV
    // measured) while the native pipeline expects a centred AC signal (the Samsung
    // front-end couples it in AC). A small alpha = cutoff
    // below 0.5 Hz, preserves the QRS.
    static double ema = Double.NaN;
    static int statBatches = 0;
    static double statMin = Double.MAX_VALUE, statMax = -Double.MAX_VALUE, statSum = 0, statSumSq = 0;
    static int statN = 0;
    static EcgTracker tracker;
    static volatile SemSensorListener sink;
    // The SHS delivers batches from several binder threads; event sequence numbers
    // must arrive in strict order or the parser aborts (-3).
    static final Object DELIVERY_LOCK = new Object();
    static final AtomicBoolean running = new AtomicBoolean(false);
    static final AtomicBoolean everReal = new AtomicBoolean(false);
    static final AtomicBoolean hiddenApiCauseLogged = new AtomicBoolean(false);
    static volatile long lastRealMs;
    static ScheduledExecutorService keepalive;
    static final AtomicInteger seq = new AtomicInteger(0);
    static final LeadDebouncer LEAD = new LeadDebouncer(EcgAdapter::log);
    static final BeatMonitor BEATS = new BeatMonitor();

    private EcgAdapter() {}

    /** Called from sa2.c(listener, 27). Returns true like the native registration. */
    public static boolean start(SemSensorListener listener, int sensorType) {
        if (sensorType != TYPE_ECG) return false;
        sink = listener;
        stopTracker();
        running.set(true);
        everReal.set(false);
        offsetsSet = false;
        baselineBuf.clear();
        ema = Double.NaN;
        statBatches = 0;
        lastRealMs = 0L;
        synchronized (DELIVERY_LOCK) { LEAD.reset(); BEATS.reset(); }
        Context ctx = appContext();
        log("start: sink=" + listener + " ctx=" + ctx);
        if (ctx == null) {
            startKeepalive();
            return true;
        }
        try {
            tracker = new EcgTracker(ctx, new Conn());
            tracker.start();
        } catch (Throwable t) {
            log("connect failed: " + t);
            tracker = null;
        }
        startKeepalive();
        return true;
    }

    /** Called from sa2.e(listener, 27). */
    public static void stop(SemSensorListener listener, int sensorType) {
        if (sensorType != TYPE_ECG) return;
        log("stop");
        running.set(false);
        stopKeepalive();
        stopTracker();
        sink = null;
    }

    static void stopTracker() {
        EcgTracker t = tracker;
        tracker = null;
        if (t != null) {
            try { t.stop(); } catch (Throwable ignored) {}
        }
    }

    // ---------------------------------------------------------------- service events

    static final class Conn implements EcgTracker.Listener {
        @Override public void onArmed() {
            log("tracker armed");
        }

        @Override public void onUnsupported() {
            log("ECG not supported by the health service");
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

        @Override public void onBatch(EcgBatch batch) {
            if (!running.get() || sink == null || batch == null || batch.samples.length == 0) return;
            synchronized (DELIVERY_LOCK) { deliverBatch(batch); }
        }

        private void deliverBatch(EcgBatch batch) {
            lastRealMs = SystemClock.elapsedRealtime();
            everReal.set(true);
            EcgSample[] points = batch.samples;
            int n = points.length;
            int[] mv = new int[n];
            for (int i = 0; i < n; i++) {
                mv[i] = points[i].hasMv()
                        ? (int) Math.round(ADC_CENTER + (double) points[i].mv / LSB_MV)
                        : ADC_CENTER;
            }
            // Lead-off is per batch and only present on data point index 0 (the client
            // already extracts it from there). The previous implementation read the last
            // point, which is always -1, so it effectively always reported contact.
            // Normalization is unchanged: 5 stays 5, anything else (0, absent) is 0.
            // Short no-contact blips are held back so they do not cancel a running measurement.
            int leadNorm = LEAD.next((batch.leadOff == 5) ? 5 : 0);
            // DC filter: filtered sample = mv - EMA(mv). The native pipeline
            // expects the signal as the Samsung front-end delivers it (AC).
            double a = 0.001; // EMA alpha (time constant ~2 s at 500 Hz)
            for (int i = 0; i < n; i++) {
                double v = (mv[i] - ADC_CENTER) * LSB_MV;
                if (Double.isNaN(ema)) ema = v;
                ema += a * (v - ema);
                double f = v - ema;
                mv[i] = (int) Math.round(ADC_CENTER + f / LSB_MV);
                if (leadNorm != 5) {
                    BEATS.feed(f);
                    statN++; statSum += f; statSumSq += f * f;
                    if (f < statMin) statMin = f;
                    if (f > statMax) statMax = f;
                }
            }
            statBatches++;
            if (statBatches % 100 == 0 && statN > 0) {
                double mean = statSum / statN;
                double rms = Math.sqrt(statSumSq / statN - mean * mean);
                log(String.format("stats: n=%d min=%.3f max=%.3f mean=%.4f rms=%.3f mV (ema=%.3f)",
                        statN, statMin, statMax, mean, rms, ema));
                String beats = BEATS.summary();
                if (beats != null) log(beats);
                statN = 0; statSum = 0; statSumSq = 0;
                statMin = Double.MAX_VALUE; statMax = -Double.MAX_VALUE;
            }
            emit(mv, leadNorm);
        }
    }

    /** Emits batches of BATCH samples as raw events. */
    static void emit(int[] mv, int lead) {
        int off = 0;
        while (off + BATCH <= mv.length) {
            push(mv, off, BATCH, lead);
            off += BATCH;
        }
    }

    static void push(int[] mv, int off, int count, int lead) {
        int[] raw = new int[LEN];
        for (int i = 0; i < count; i++) {
            raw[4 + i] = mv[off + i];
        }
        raw[14] = (lead & 0xFF) << 16;
        int s = seq.getAndIncrement() & 0xFF;
        raw[15] = ((count & 0xFF) << 8) | s;
        raw[25] = bandHiAdc;
        raw[26] = bandLoAdc;
        deliver(raw);
    }

    static void deliver(int[] raw) {
        SemSensorListener l = sink;
        if (l == null || !running.get()) return;
        try {
            Bundle b = new Bundle();
            b.putIntArray("value", raw);
            SemEcgSensorEvent ev = new SemEcgSensorEvent(TYPE_ECG, b);
            l.onEventChanged(ev);
        } catch (Throwable t) {
            if (t instanceof LinkageError && hiddenApiCauseLogged.compareAndSet(false, true)) {
                log("deliver failed: hidden-API enforcement on One UI 8 Watch denies the"
                        + " SemEcgSensorEvent constructor to this app (" + t + ")");
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
                        int[] raw = new int[LEN];
                        for (int i = 4; i < 4 + BATCH; i++) raw[i] = ADC_CENTER;
                        raw[14] = 5 << 16;
                        int s = seq.getAndIncrement() & 0xFF;
                        raw[15] = (BATCH << 8) | s;
                        raw[25] = ADC_CENTER;
                        raw[26] = ADC_CENTER;
                        deliver(raw);
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
