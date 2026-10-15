package shminterop.spo2;

import android.os.Bundle;
import com.samsung.android.hardware.sensormanager.SemSensorAttribute;
import com.samsung.android.hardware.sensormanager.SemSensorEvent;
import com.samsung.android.hardware.sensormanager.SemSensorListener;
import com.samsung.android.hardware.sensormanager.SemSleepWatchSpO2SensorAttribute.Cmd;
import com.samsung.android.hardware.sensormanager.SemSleepWatchSpO2SensorEvent;
import com.samsung.android.hardware.sensormanager.SemSleepWatchSpO2SensorParam;
import shminterop.hsdk.PpgBatch;
import shminterop.hsdk.PpgSample;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plain main-based test of the SpO2Adapter flow with behavioural doubles (run-tests.sh only):
 * register, GET_VERSION, SET_OPERATION_MODE, fed PPG batches, GET_DATA (chunked, EMPTY, dense
 * grid), simulation mode, unregister. PpgTracker is a shadow, so nothing touches the service.
 * Exits non-zero on failure.
 */
public final class SpO2AdapterTest {
    private static int failures;
    static final long T0 = 1_700_000_000_000L;
    static volatile String simValue = "";

    public static void main(String[] args) throws Exception {
        SysProp.source = new SysProp.Source() {
            @Override public String get(String key) {
                return SpO2Adapter.SIM_PROP.equals(key) ? simValue : "";
            }
        };
        versionEvent();
        fullFlow();
        simulation();
        unknownCommands();
        SpO2Adapter.resetForTest();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

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
        final List<Bundle> events = new CopyOnWriteArrayList<Bundle>();

        @Override public void onEventChanged(SemSensorEvent event) {
            SemSleepWatchSpO2SensorEvent e = (SemSleepWatchSpO2SensorEvent) event;
            check(e.getType() == 49, "event carries sensor type 49");
            events.add(e.getBundle());
        }

        @Override public void onStatusChanged(int status, String s) {
        }

        List<Bundle> await(int n) throws InterruptedException {
            long end = System.currentTimeMillis() + 5000;
            while (events.size() < n && System.currentTimeMillis() < end) Thread.sleep(10);
            return events;
        }
    }

    static SemSensorAttribute attr(EnumSet<Cmd> cmds, Object... kv) {
        Bundle b = new Bundle();
        b.putSerializable("cmd", cmds);
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            if (kv[i + 1] instanceof Long) b.putLong(k, (Long) kv[i + 1]);
            else b.putSerializable(k, (java.io.Serializable) kv[i + 1]);
        }
        SemSensorAttribute a = new SemSensorAttribute();
        a.setAttribute(49, b);
        return a;
    }

    static boolean getData(SemSensorListener l, long start, long end) {
        return SpO2Adapter.setAttribute(l, 49, attr(EnumSet.of(Cmd.GET_DATA),
                "startTimestamp", start, "endTimestamp", end));
    }

    /** 1.2 Hz pulses with a known R under the adapter's channel orientation. */
    static PpgBatch pulses(int firstSample, int count, double r) {
        PpgSample[] s = new PpgSample[count];
        for (int k = 0; k < count; k++) {
            int i = firstSample + k;
            double p = Math.sin(2 * Math.PI * 1.2 * i / 100.0);
            int algoRed = (int) Math.round(100000 * (1 + 0.01 * r * p));
            int algoIr = (int) Math.round(120000 * (1 + 0.01 * p));
            int labelledRed = SpO2Adapter.SWAP_RED_IR ? algoIr : algoRed;
            int labelledIr = SpO2Adapter.SWAP_RED_IR ? algoRed : algoIr;
            s[k] = new PpgSample(T0 + i * 10L, 0, 0, labelledRed, 0, labelledIr, 0);
        }
        return new PpgBatch(s);
    }

    static void versionEvent() throws Exception {
        SpO2Adapter.resetForTest();
        Recorder l = new Recorder();
        check(SpO2Adapter.register(l, 49), "register returns true");
        for (SemSleepWatchSpO2SensorParam.VersionType vt : SemSleepWatchSpO2SensorParam.VersionType.values()) {
            l.events.clear();
            check(SpO2Adapter.setAttribute(l, 49, attr(EnumSet.of(Cmd.GET_VERSION), "versionType", vt)),
                    "GET_VERSION returns true");
            List<Bundle> ev = l.await(1);
            eq(1, ev.size(), "one VERSION event for " + vt);
            Bundle b = ev.get(0);
            eq(SemSleepWatchSpO2SensorParam.DataType.VERSION, b.getSerializable("data_type"), "data_type");
            eq(vt, b.getSerializable("version_type"), "version_type echoes the request");
            int[] v = b.getIntArray("version");
            check(v != null && v.length == 3, "version int[3]");
            @SuppressWarnings("unchecked")
            HashMap<Integer, int[]> map = (HashMap<Integer, int[]>) b.getSerializable("versionMap");
            check(map != null && map.size() == 1 && map.containsKey(1) && !map.containsKey(2),
                    "versionMap holds key 1 only");
            check(map != null && map.get(1) != null && map.get(1).length == 3, "versionMap value int[3]");
        }
        SpO2Adapter.unregister(l, 49);
    }

    static void fullFlow() throws Exception {
        simValue = "";
        SpO2Adapter.resetForTest();
        Recorder l = new Recorder();
        SpO2Adapter.register(l, 49);

        check(SpO2Adapter.setAttribute(l, 49, attr(EnumSet.of(Cmd.SET_OPERATION_MODE),
                "mode", SemSleepWatchSpO2SensorParam.OperationMode.HM_SPO2_VERSION_1)),
                "SET_OPERATION_MODE returns true");
        check(SpO2Adapter.isRecording(), "recording after SET_OPERATION_MODE");
        check(SpO2Adapter.setAttribute(l, 49, attr(EnumSet.of(Cmd.FLUSH))), "FLUSH returns true");
        eq(0, l.events.size(), "FLUSH sends nothing");

        // 30 s of pulses, R = 0.7, delivered in 50-sample batches like the service does.
        for (int i = 0; i < 3000; i += 50) {
            SpO2Adapter.trackerListener().onBatch(pulses(i, 50, 0.7));
        }
        int expected = Spo2Estimator.fromR(0.7);

        check(getData(l, T0, T0 + 29_000L), "GET_DATA returns true");
        List<Bundle> ev = l.await(1);
        eq(1, ev.size(), "one NORMAL chunk");
        Bundle b = ev.get(0);
        eq(SemSleepWatchSpO2SensorParam.DataType.NORMAL, b.getSerializable("data_type"), "NORMAL");
        eq(30, b.getInt("secFullSize"), "secFullSize");
        eq(30, b.getInt("secSize"), "secSize");
        eq(0, b.getInt("secRemainCount"), "secRemainCount");
        int[] v = b.getIntArray("secSpO2List");
        long[] t = b.getLongArray("secTimeList");
        eq(30, v.length, "spo2 length");
        eq(30, t.length, "time length");
        eq(30, b.getShortArray("dcRatioList").length, "dcRatio zeros length");
        eq(30, b.getShortArray("accPowerList").length, "accPower zeros length");
        for (int i = 0; i < 30; i++) {
            eq(T0 + i * 1000L, t[i], "time " + i);
            if (i < 7 || i == 29) eq(0, v[i], "second " + i + " has no value");
            else check(Math.abs(v[i] - expected) <= 1, "second " + i + " value " + v[i] + " ~ " + expected);
        }

        // Empty window.
        l.events.clear();
        getData(l, T0 + 5000L, T0 + 5000L);
        ev = l.await(1);
        eq(1, ev.size(), "one EMPTY event");
        eq(SemSleepWatchSpO2SensorParam.DataType.EMPTY, ev.get(0).getSerializable("data_type"), "EMPTY");
        check(ev.get(0).getIntArray("secSpO2List") == null, "EMPTY has no list");

        // Chunked answer, sequential, the first one immediately available.
        l.events.clear();
        getData(l, T0, T0 + 1499_000L);
        ev = l.await(3);
        eq(3, ev.size(), "three chunks for 1500 s");
        int[] sizes = {600, 600, 300};
        int[] remain = {900, 300, 0};
        for (int i = 0; i < 3 && i < ev.size(); i++) {
            eq(1500, ev.get(i).getInt("secFullSize"), "chunk " + i + " full size");
            eq(sizes[i], ev.get(i).getInt("secSize"), "chunk " + i + " size");
            eq(remain[i], ev.get(i).getInt("secRemainCount"), "chunk " + i + " remain");
        }

        // Unregister stops the recording; the stored night stays answerable, also to others.
        SpO2Adapter.unregister(l, 49);
        check(!SpO2Adapter.isRecording(), "not recording after unregister");
        Recorder other = new Recorder();
        getData(other, T0, T0 + 29_000L);
        ev = other.await(1);
        eq(1, ev.size(), "answer after unregister goes to the calling listener");
        check(Math.abs(ev.get(0).getIntArray("secSpO2List")[10] - expected) <= 1, "stored value survives");
        eq(0, l.events.size() - 3, "no stray events to the unregistered listener");

        // A new SET_OPERATION_MODE long after the newest second starts a new night (store cleared).
        SpO2Adapter.setAttribute(other, 49, attr(EnumSet.of(Cmd.SET_OPERATION_MODE),
                "mode", SemSleepWatchSpO2SensorParam.OperationMode.HM_SPO2_VERSION_1));
        other.events.clear();
        getData(other, T0, T0 + 29_000L);
        ev = other.await(1);
        int nonZero = 0;
        for (int x : ev.get(0).getIntArray("secSpO2List")) if (x != 0) nonZero++;
        eq(0, nonZero, "new night clears the old values");
        SpO2Adapter.unregister(other, 49);
    }

    static void simulation() throws Exception {
        SpO2Adapter.resetForTest();
        simValue = "1:5";
        Recorder l = new Recorder();
        SpO2Adapter.register(l, 49);
        long end = T0 + 7_200_000L;
        getData(l, end - 60_000L, end); // the requested start is ignored in simulation mode
        List<Bundle> ev = l.await(6);
        eq(6, ev.size(), "1 h = 3600 entries = 6 chunks");
        int min = 100, count = 0;
        long lastT = 0;
        for (Bundle b : ev) {
            eq(3600, b.getInt("secFullSize"), "sim full size");
            for (int x : b.getIntArray("secSpO2List")) {
                min = Math.min(min, x);
                count++;
                check(x >= 70 && x <= 100, "sim value in range " + x);
            }
            long[] t = b.getLongArray("secTimeList");
            lastT = t[t.length - 1];
        }
        eq(3600, count, "sim entries");
        eq(end, lastT, "sim night ends at endTimestamp");
        check(min <= 92, "sim night contains desaturations: " + min);
        simValue = "";
        SpO2Adapter.unregister(l, 49);
    }

    static void unknownCommands() {
        SpO2Adapter.resetForTest();
        Recorder l = new Recorder();
        check(SpO2Adapter.setAttribute(l, 49, null), "null attribute returns true");
        check(SpO2Adapter.setAttribute(l, 49, new SemSensorAttribute()), "attribute without bundle returns true");
        check(SpO2Adapter.setAttribute(l, 49, attr(EnumSet.noneOf(Cmd.class))), "empty command set returns true");
        Bundle odd = new Bundle();
        odd.putSerializable("cmd", "not an enum set");
        SemSensorAttribute a = new SemSensorAttribute();
        a.setAttribute(49, odd);
        check(SpO2Adapter.setAttribute(l, 49, a), "garbage cmd returns true");
        SpO2Adapter.unregister(l, 49); // never registered: harmless
        SpO2Adapter.unregister(null, 49);
    }
}
