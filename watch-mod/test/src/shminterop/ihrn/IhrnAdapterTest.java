package shminterop.ihrn;

import com.samsung.android.hardware.sensormanager.SemPpgIhrnSensorAttribute;
import com.samsung.android.hardware.sensormanager.SemPpgIhrnSensorEvent;
import com.samsung.android.hardware.sensormanager.SemPpgIhrnSensorParam;
import com.samsung.android.hardware.sensormanager.SemSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorListener;
import shminterop.hsdk.AccelBatch;
import shminterop.hsdk.AccelSample;
import shminterop.hsdk.GreenAccelTracker;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain main-based tests of the IHRN adapter (run-tests.sh only): the 1 -> 250 mode switch, the
 * event contents against the getters SHM reads, pairing by timestamp and its timeout, the
 * compromised-window flags (gap, pairing, level step, status), motion, sequence numbers, and the
 * session lifecycle (late callbacks of a stopped session, nothing after unregister). The tracker
 * is a shadow, so the tests feed the listener the adapter registered. Exits non-zero on failure.
 */
public final class IhrnAdapterTest {
    private static int failures;
    static final long T0 = 1_700_000_000_000L;
    static final AtomicLong NOW = new AtomicLong(0);

    public static void main(String[] args) throws Exception {
        IhrnAdapter.clock = new IhrnAdapter.Clock() {
            @Override public long now() { return NOW.get(); }
        };
        singleThenBatch();
        eventContents();
        pairingWaitsForAccel();
        pairingTimeoutMarksCompromised();
        gapMarksCompromised();
        lateBurstIsNotAGap();
        statusMarksOffWrist();
        warmUpSamplesNotDelivered();
        warmUpEndsAtTheLimit();
        levelStepMarksCompromised();
        motionThreshold();
        duplicateTimestampsDropped();
        sequenceIncrements();
        modeSwitchBackDropsPartialBatch();
        staleSessionDeliversNothing();
        nothingAfterUnregister();
        unregisterOfOtherListenerIgnored();
        converterUnits();
        windowLogLine();
        reset();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- helpers

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + " expected=" + expected + " actual=" + actual);
    }

    static final class Recorder implements SemSensorListener {
        final List<SemPpgIhrnSensorEvent> events = new CopyOnWriteArrayList<SemPpgIhrnSensorEvent>();

        @Override public void onEventChanged(SemSensorEvent event) {
            events.add((SemPpgIhrnSensorEvent) event);
        }

        @Override public void onStatusChanged(int status, String s) {
        }
    }

    static void reset() {
        IhrnAdapter.resetForTest();
        NOW.set(0);
    }

    static Recorder start() {
        reset();
        IhrnAdapter.tickMs = 3_600_000L; // the tests tick by hand
        IhrnAdapter.flushMs = 3_600_000L;
        Recorder r = new Recorder();
        IhrnAdapter.register(r, 0x25);
        return r;
    }

    static void batchMode(Recorder r, boolean on) {
        SemPpgIhrnSensorAttribute a = new SemPpgIhrnSensorAttribute();
        a.setBatchMode(on ? SemPpgIhrnSensorParam.BatchMode.MAX : SemPpgIhrnSensorParam.BatchMode.ONE);
        IhrnAdapter.setAttribute(r, 0x25, a);
    }

    static PpgSample green(long ts, int v, int status) {
        return new PpgSample(ts, v, status);
    }

    static GreenAccelTracker.Listener feedGreen(GreenAccelTracker.Listener l, PpgSample... s) {
        l.onGreen(new PpgBatch(s));
        return l;
    }

    static void feedAccel(GreenAccelTracker.Listener l, AccelSample... s) {
        l.onAccel(new AccelBatch(s));
    }

    /** n paired samples from index [from], 40 ms apart, constant green and 1 g on z. */
    static void feedPairs(GreenAccelTracker.Listener l, int from, int n) throws Exception {
        PpgSample[] g = new PpgSample[n];
        AccelSample[] a = new AccelSample[n];
        for (int i = 0; i < n; i++) {
            long ts = T0 + 40L * (from + i);
            g[i] = green(ts, 2_180_000 + (i % 7), 0);
            a[i] = new AccelSample(ts, 5, -7, 4096);
        }
        l.onGreen(new PpgBatch(g));
        l.onAccel(new AccelBatch(a));
        IhrnAdapter.settle();
    }

    // ---------------------------------------------------------------- tests

    static void singleThenBatch() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        eq(false, IhrnAdapter.isBatchMode(), "starts in single mode");
        feedPairs(l, 0, 3);
        eq(3, r.events.size(), "three single events");
        for (SemPpgIhrnSensorEvent e : r.events) {
            eq(1, e.getCount(), "single count");
            eq(1, e.getSystickList().length, "single array length");
            eq(SemPpgIhrnSensorParam.EventType.NORMAL, e.getEventType(), "event type");
        }
        batchMode(r, true);
        eq(true, IhrnAdapter.isBatchMode(), "batch mode after MAX");
        feedPairs(l, 3, 249);
        eq(3, r.events.size(), "no event until 250 samples");
        feedPairs(l, 252, 1);
        eq(4, r.events.size(), "one batch event");
        eq(250, r.events.get(3).getCount(), "batch count");
        eq(250, r.events.get(3).getLedGreenList().length, "batch led array length");
        feedPairs(l, 253, 500);
        eq(6, r.events.size(), "two more batch events");
        for (int i = 3; i < 6; i++) eq(250, r.events.get(i).getCount(), "count 250 only");
        reset();
    }

    static void eventContents() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedGreen(l, green(T0, 2_200_000, 0));
        feedAccel(l, new AccelSample(T0, 11, -22, 4000));
        IhrnAdapter.settle();
        eq(1, r.events.size(), "one event");
        SemPpgIhrnSensorEvent e = r.events.get(0);
        eq(2_200_000L, e.getLedGreenList()[0], "green passes through");
        eq(11, e.getAccXArray()[0], "acc x passes through");
        eq(-22, e.getAccYArray()[0], "acc y passes through");
        eq(4000, e.getAccZArray()[0], "acc z passes through");
        eq(T0, e.getSystickList()[0], "systick_array = sample ms");
        eq(T0, e.getTimestamp(), "timestamp = first sample ms");
        eq(T0, e.getSystick(), "systick");
        eq(true, e.getWristList()[0], "wrist when status 0");
        eq(false, e.getMotionList()[0], "no motion on the first sample");
        eq(false, e.getAgcList()[0], "first clean sample not compromised");
        eq(false, e.getEcgModeList()[0], "ecg_mode false");
        eq(0, e.getModeList()[0], "mode 0");
        eq(SemPpgIhrnSensorParam.Accuracy.NORMAL, e.getAccuracy(), "accuracy NORMAL");
        eq(-1, e.getDataSequence(), "single events carry no sequence");
        reset();
    }

    static void pairingWaitsForAccel() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedGreen(l, green(T0, 2_000_000, 0), green(T0 + 40, 2_000_001, 0));
        IhrnAdapter.settle();
        eq(0, r.events.size(), "green alone is held back");
        feedAccel(l, new AccelSample(T0 + 40, 1, 2, 3)); // second only: the first still blocks the order
        IhrnAdapter.settle();
        eq(0, r.events.size(), "head still unmatched");
        feedAccel(l, new AccelSample(T0, 9, 9, 9));
        IhrnAdapter.settle();
        eq(2, r.events.size(), "both leave once the head is matched");
        eq(9, r.events.get(0).getAccXArray()[0], "first pair accel");
        eq(1, r.events.get(1).getAccXArray()[0], "second pair accel");
        eq(false, r.events.get(0).getAgcList()[0], "matched pair not compromised");
        eq(false, r.events.get(1).getAgcList()[0], "matched pair not compromised [1]");
        reset();
    }

    static void pairingTimeoutMarksCompromised() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedGreen(l, green(T0, 2_000_000, 0));
        feedAccel(l, new AccelSample(T0 - 40, 7, 8, 4096)); // an older, unrelated accel sample
        IhrnAdapter.settle();
        eq(0, r.events.size(), "held before the timeout");
        NOW.set(IhrnPairer.HOLD_MS - 1);
        IhrnAdapter.tickNow();
        eq(0, r.events.size(), "still held just before the timeout");
        NOW.set(IhrnPairer.HOLD_MS);
        IhrnAdapter.tickNow();
        eq(1, r.events.size(), "emitted at the timeout");
        eq(true, r.events.get(0).getAgcList()[0], "unmatched sample is compromised");
        eq(7, r.events.get(0).getAccXArray()[0], "last accel is used");
        reset();
    }

    static void gapMarksCompromised() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        long[] ts = {T0, T0 + 74, T0 + 120, T0 + 120 + 121, T0 + 120 + 121 + 40};
        for (long t : ts) {
            feedGreen(l, green(t, 2_100_000, 0));
            feedAccel(l, new AccelSample(t, 0, 0, 4096));
        }
        IhrnAdapter.settle();
        eq(5, r.events.size(), "five events");
        eq(false, r.events.get(1).getAgcList()[0], "74 ms jitter is not a gap");
        eq(false, r.events.get(2).getAgcList()[0], "46 ms is not a gap");
        eq(true, r.events.get(3).getAgcList()[0], "121 ms is a gap");
        eq(false, r.events.get(4).getAgcList()[0], "the sample after a gap is clean again");
        reset();
    }

    static void lateBurstIsNotAGap() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        NOW.set(50_000); // the burst arrives 12 s after its samples were taken
        feedPairs(l, 0, 300);
        eq(300, r.events.size(), "all samples forwarded in order");
        boolean anyAgc = false;
        for (SemPpgIhrnSensorEvent e : r.events) anyAgc |= e.getAgcList()[0];
        eq(false, anyAgc, "late arrival is not a gap");
        eq(T0, r.events.get(0).getSystickList()[0], "original timestamps kept (first)");
        eq(T0 + 40L * 299, r.events.get(299).getSystickList()[0], "original timestamps kept (last)");
        reset();
    }

    static void statusMarksOffWrist() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedGreen(l, green(T0, 2_100_000, 0), green(T0 + 40, 2_100_050, -999), green(T0 + 80, 2_100_100, 500));
        feedAccel(l, new AccelSample(T0, 0, 0, 4096), new AccelSample(T0 + 40, 0, 0, 4096),
                new AccelSample(T0 + 80, 0, 0, 4096));
        IhrnAdapter.settle();
        eq(true, r.events.get(0).getWristList()[0], "status 0 -> wrist");
        eq(false, r.events.get(1).getWristList()[0], "status -999 -> not wrist");
        eq(true, r.events.get(1).getAgcList()[0], "status -999 -> compromised");
        eq(false, r.events.get(2).getWristList()[0], "status 500 -> not wrist");
        eq(true, r.events.get(2).getAgcList()[0], "status 500 -> compromised");
        reset();
    }

    static void warmUpSamplesNotDelivered() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedGreen(l, green(T0, 2_097_152, 1), green(T0 + 40, 2_097_152, 1), green(T0 + 80, 2_100_000, 0),
                green(T0 + 120, 2_100_050, 1));
        feedAccel(l, new AccelSample(T0, 0, 0, 4096), new AccelSample(T0 + 40, 0, 0, 4096),
                new AccelSample(T0 + 80, 0, 0, 4096), new AccelSample(T0 + 120, 0, 0, 4096));
        IhrnAdapter.settle();
        eq(2, r.events.size(), "off-wrist samples before the first on-wrist one are not delivered");
        eq(T0 + 80, r.events.get(0).getSystickList()[0], "first delivered sample is the first on-wrist one");
        eq(true, r.events.get(0).getWristList()[0], "first delivered sample is on the wrist");
        eq(false, r.events.get(1).getWristList()[0], "off-wrist after the warm-up is delivered");
        reset();
    }

    static void warmUpEndsAtTheLimit() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        NOW.set(IhrnAdapter.WARMUP_MAX_MS - 1);
        feedGreen(l, green(T0, 2_097_152, 1));
        feedAccel(l, new AccelSample(T0, 0, 0, 4096));
        IhrnAdapter.settle();
        eq(0, r.events.size(), "off-wrist sample inside the limit is not delivered");
        NOW.set(IhrnAdapter.WARMUP_MAX_MS);
        feedGreen(l, green(T0 + 40, 2_097_152, 1));
        feedAccel(l, new AccelSample(T0 + 40, 0, 0, 4096));
        IhrnAdapter.settle();
        eq(1, r.events.size(), "off-wrist sample at the limit is delivered");
        eq(false, r.events.get(0).getWristList()[0], "delivered as off-wrist");
        reset();
    }

    static void levelStepMarksCompromised() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        int[] v = {2_000_000, 2_040_000, 2_800_000, 2_810_000};
        for (int i = 0; i < v.length; i++) {
            feedGreen(l, green(T0 + 40L * i, v[i], 0));
            feedAccel(l, new AccelSample(T0 + 40L * i, 0, 0, 4096));
        }
        IhrnAdapter.settle();
        eq(false, r.events.get(1).getAgcList()[0], "2 % step is signal");
        eq(true, r.events.get(2).getAgcList()[0], "37 % step is a gain change");
        eq(false, r.events.get(3).getAgcList()[0], "small step after it is clean");
        reset();
    }

    static void motionThreshold() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        for (int i = 0; i < 25; i++) { // still: 1 g with +-100 counts of noise (std about 100)
            feedGreen(l, green(T0 + 40L * i, 2_100_000, 0));
            feedAccel(l, new AccelSample(T0 + 40L * i, 0, 0, 4096 + (i % 2 == 0 ? 100 : -100)));
        }
        IhrnAdapter.settle();
        boolean any = false;
        for (SemPpgIhrnSensorEvent e : r.events) any |= e.getMotionList()[0];
        eq(false, any, "still wrist: no motion");
        for (int i = 25; i < 50; i++) { // moving: +-800 counts (std about 800)
            feedGreen(l, green(T0 + 40L * i, 2_100_000, 0));
            feedAccel(l, new AccelSample(T0 + 40L * i, 0, 0, 4096 + (i % 2 == 0 ? 800 : -800)));
        }
        IhrnAdapter.settle();
        eq(true, r.events.get(r.events.size() - 1).getMotionList()[0], "moving wrist: motion");
        reset();
    }

    static void duplicateTimestampsDropped() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedPairs(l, 0, 3);
        feedGreen(l, green(T0 + 40, 2_000_000, 0), green(T0, 2_000_000, 0));
        IhrnAdapter.settle();
        eq(3, r.events.size(), "a repeated or older hub sample is not delivered again");
        reset();
    }

    static void sequenceIncrements() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        batchMode(r, true);
        feedPairs(l, 0, 750);
        eq(3, r.events.size(), "three batches");
        eq(0, r.events.get(0).getDataSequence(), "first sequence 0");
        eq(1, r.events.get(1).getDataSequence(), "then 1");
        eq(2, r.events.get(2).getDataSequence(), "then 2");
        // a new registration starts again at 0 (SHM resets its expectation per session)
        Recorder r2 = new Recorder();
        IhrnAdapter.register(r2, 0x25);
        batchMode(r2, true);
        GreenAccelTracker.Listener l2 = IhrnAdapter.trackerListener();
        feedPairs(l2, 2000, 250);
        eq(1, r2.events.size(), "batch in the new session");
        eq(0, r2.events.get(0).getDataSequence(), "sequence restarts per session");
        reset();
    }

    static void modeSwitchBackDropsPartialBatch() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        batchMode(r, true);
        feedPairs(l, 0, 100);
        batchMode(r, false);
        feedPairs(l, 100, 2);
        eq(2, r.events.size(), "back to single: the partial batch is dropped, singles flow");
        eq(1, r.events.get(0).getCount(), "single count");
        reset();
    }

    static void staleSessionDeliversNothing() throws Exception {
        Recorder a = start();
        GreenAccelTracker.Listener lateA = IhrnAdapter.trackerListener();
        Recorder b = new Recorder();
        IhrnAdapter.register(b, 0x25);
        GreenAccelTracker.Listener lb = IhrnAdapter.trackerListener();
        feedPairs(lateA, 0, 5); // late callbacks of the stopped first session
        IhrnAdapter.settle();
        eq(0, a.events.size(), "stopped session delivers nothing");
        eq(0, b.events.size(), "late batch of the old session never reaches the new one");
        feedPairs(lb, 0, 2);
        eq(2, b.events.size(), "the new session works");
        reset();
    }

    static void nothingAfterUnregister() throws Exception {
        Recorder r = start();
        GreenAccelTracker.Listener l = IhrnAdapter.trackerListener();
        feedPairs(l, 0, 2);
        IhrnAdapter.unregister(r, 0x25);
        eq(false, IhrnAdapter.isActive(), "inactive after unregister");
        feedPairs(l, 2, 5);
        eq(2, r.events.size(), "no event after unregister");
        IhrnAdapter.unregister(r, 0x25); // idempotent
        reset();
    }

    static void unregisterOfOtherListenerIgnored() throws Exception {
        Recorder r = start();
        IhrnAdapter.unregister(new Recorder(), 0x25);
        eq(true, IhrnAdapter.isActive(), "a different listener does not stop the session");
        IhrnAdapter.unregister(r, 0x25);
        reset();
    }

    static void converterUnits() {
        IhrnConverter c = new IhrnConverter();
        IhrnSample s = c.convert(new PairedSample(T0, 123456, 0, 1, 2, 3, false));
        eq(123456L, s.ledGreen, "identity conversion");
        eq(true, s.wrist, "wrist");
        eq(1, IhrnConverter.GREEN_SIGN, "sign +1");
        eq(0L, IhrnConverter.GREEN_OFFSET, "offset 0");
        c.reset();
        // reset forgets the previous timestamp: no gap against the old session
        c.convert(new PairedSample(T0, 1, 0, 0, 0, 0, false));
        c.reset();
        eq(false, c.convert(new PairedSample(T0 + 10_000, 1, 0, 0, 0, 0, false)).agc, "no gap after reset");
    }

    static void windowLogLine() {
        IhrnWindowLog log = new IhrnWindowLog();
        String line = null;
        IhrnConverter c = new IhrnConverter();
        for (int i = 0; i < IhrnWindowLog.WINDOW; i++) {
            String got = log.add(c.convert(new PairedSample(T0 + 40L * i, 2_000_000 + i, 0, 0, 0, 4096, false)));
            if (i < IhrnWindowLog.WINDOW - 1) check(got == null, "no line before the window is full");
            else line = got;
        }
        check(line != null && line.contains("rate=25.0 Hz") && line.contains("gaps=0")
                && line.contains("compromised=0"), "window line: " + line);
    }
}
