package shminterop.bp;

import com.samsung.android.hardware.sensormanager.SemBpManualSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorListener;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain main-based tests of the BpAdapter repackaging layer (run-tests.sh only): the green ADC
 * count passed through unchanged into a "green_raw" bundle, one event per sample, the
 * sink/running guards, and the flatline keepalive that feeds the pipeline while no real data
 * flows (and goes quiet as soon as it does). Android and vendor types come from the behavioural
 * doubles in the test tree; PpgTracker is a shadow, so nothing here touches the real service.
 * Exits non-zero on failure.
 */
public final class BpAdapterTest {
    private static int failures;

    public static void main(String[] args) throws Exception {
        deliverPassesGreenThrough();
        oneEventPerSample();
        guardsSwallowDelivery();
        keepaliveFlatlineStopsOnRealData();
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

    /** Records the events the adapter repackages; called from binder and keepalive threads. */
    static final class Recorder implements SemSensorListener {
        final List<SemBpManualSensorEvent> events = new CopyOnWriteArrayList<SemBpManualSensorEvent>();

        @Override public void onEventChanged(SemSensorEvent event) {
            events.add((SemBpManualSensorEvent) event);
        }

        @Override public void onStatusChanged(int status, String s) {
        }
    }

    static PpgBatch batch(int... adcValues) {
        PpgSample[] samples = new PpgSample[adcValues.length];
        for (int i = 0; i < adcValues.length; i++) {
            samples[i] = new PpgSample(1000L + i * 10L, adcValues[i],
                    adcValues[i] < 0 ? PpgBatch.STATUS_ERROR : PpgBatch.STATUS_NORMAL);
        }
        return new PpgBatch(samples);
    }

    /** Back to the idle state, whatever a previous test left behind. */
    static void reset() {
        BpAdapter.stopKeepalive();
        BpAdapter.stopTracker();
        BpAdapter.sink = null;
        BpAdapter.running.set(false);
        BpAdapter.everReal.set(false);
        BpAdapter.hiddenApiCauseLogged.set(false);
        BpAdapter.lastRealMs = 0L;
        synchronized (BpAdapter.DELIVERY_LOCK) {
            BpAdapter.resetStats();
        }
    }

    // ---------------------------------------------------------------- tests

    static void deliverPassesGreenThrough() {
        reset();
        Recorder recorder = new Recorder();
        BpAdapter.sink = recorder;
        BpAdapter.running.set(true);
        BpAdapter.deliver(500);
        eq(1, recorder.events.size(), "deliver emits one event");
        if (!recorder.events.isEmpty()) {
            SemBpManualSensorEvent event = recorder.events.get(0);
            eq(500, event.getGreenRaw(), "green_raw round trip");
            eq(500, event.getBundle().getInt(BpAdapter.GREEN_RAW_KEY),
                    "bundle carries the value under the consumer key");
        }
    }

    static void oneEventPerSample() {
        reset();
        Recorder recorder = new Recorder();
        BpAdapter.sink = recorder;
        BpAdapter.running.set(true);
        // One sample per delivery with a per-sample quality flag (4.4): the middle sample is
        // flagged abnormal and is still fed, like the vendor HAL path does.
        new BpAdapter.Conn().onBatch(batch(111, -222, 333));
        eq(3, recorder.events.size(), "one event per sample");
        if (recorder.events.size() == 3) {
            eq(111, recorder.events.get(0).getGreenRaw(), "sample 0 adc");
            eq(-222, recorder.events.get(1).getGreenRaw(), "sample 1 adc (kept, abnormal)");
            eq(333, recorder.events.get(2).getGreenRaw(), "sample 2 adc");
        }
        check(BpAdapter.everReal.get(), "everReal after a real batch");
        synchronized (BpAdapter.DELIVERY_LOCK) {
            eq(3, BpAdapter.statN, "statN counts fed samples");
            eq(1, BpAdapter.statBad, "statBad counts abnormal samples");
        }

        // An empty or null batch delivers nothing.
        int before = recorder.events.size();
        new BpAdapter.Conn().onBatch(new PpgBatch(new PpgSample[0]));
        new BpAdapter.Conn().onBatch(null);
        eq(before, recorder.events.size(), "empty and null batches deliver nothing");
    }

    static void guardsSwallowDelivery() {
        reset();
        BpAdapter.sink = null;
        BpAdapter.running.set(true);
        BpAdapter.deliver(7); // no sink: must be a no-op, not a crash

        Recorder recorder = new Recorder();
        BpAdapter.sink = recorder;
        BpAdapter.running.set(false);
        new BpAdapter.Conn().onBatch(batch(1, 2));
        BpAdapter.deliver(7);
        eq(0, recorder.events.size(), "nothing is delivered while stopped");
    }

    static void keepaliveFlatlineStopsOnRealData() throws Exception {
        reset();
        Recorder recorder = new Recorder();
        // Host path: no ActivityThread, so start() runs with the tracker == null, exactly the
        // "service missing" case; the keepalive is the only feed.
        BpAdapter.start(recorder, BpAdapter.TYPE_BP);
        check(BpAdapter.tracker == null, "no tracker on the host");
        check(BpAdapter.keepalive != null, "keepalive armed");

        long deadline = System.currentTimeMillis() + 5000L;
        while (recorder.events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        check(!recorder.events.isEmpty(), "flatline arrives while the service is missing");
        for (SemBpManualSensorEvent event : recorder.events) {
            eq(0, event.getGreenRaw(), "flatline is green_raw=0");
        }
        int seen = recorder.events.size();

        // Real data: the flatline must stop, not interleave.
        new BpAdapter.Conn().onBatch(batch(555));
        Thread.sleep(400L); // several keepalive ticks
        eq(seen + 1, recorder.events.size(), "keepalive goes quiet once real data flows");

        // A non-BP registration never touches the state.
        BpAdapter.start(new Recorder(), 27);
        check(BpAdapter.sink == recorder, "start ignores non-BP types");
        check(BpAdapter.keepalive != null, "non-BP start does not reset the keepalive");

        BpAdapter.stop(recorder, BpAdapter.TYPE_BP);
        int afterStop = recorder.events.size();
        Thread.sleep(250L);
        eq(afterStop, recorder.events.size(), "stop ends all deliveries");
        check(BpAdapter.keepalive == null, "keepalive is down after stop");
        BpAdapter.stop(recorder, 27); // ignored, must not resurrect anything
        check(!BpAdapter.running.get(), "stays stopped");
    }
}
