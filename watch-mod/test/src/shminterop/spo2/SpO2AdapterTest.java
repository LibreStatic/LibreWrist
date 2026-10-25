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

import android.content.Context;
import android.os.PowerManager;

import java.io.File;
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
        SpO2Adapter.fallbackDelayMs = 3_600_000L; // the tests call fallbackCheck directly
        versionEvent();
        fullFlow();
        simulation();
        unknownCommands();
        answerCoverage();
        resumeWindow();
        autoResume();
        fallbackStart();
        orientationFlip();
        orientationKept();
        nightDiagnostics();
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

    // ------------------------------------------------------------ night robustness (issue #13)

    static class TestContext extends Context {
        final File dir;
        final PowerManager pm = new PowerManager();

        TestContext(String name) {
            File base = new File(System.getProperty("test.tmp", "."), "spo2-adapter-test");
            dir = new File(base, name + "-" + System.nanoTime());
            dir.mkdirs();
        }

        @Override public File getFilesDir() {
            return dir;
        }

        @Override public Object getSystemService(String name) {
            return POWER_SERVICE.equals(name) ? pm : null;
        }
    }

    static final Object SOM_MODE = SemSleepWatchSpO2SensorParam.OperationMode.HM_SPO2_VERSION_1;

    static void startNight(SemSensorListener l) {
        SpO2Adapter.setAttribute(l, 49, attr(EnumSet.of(Cmd.SET_OPERATION_MODE), "mode", SOM_MODE));
    }

    static long nowSecond() {
        return System.currentTimeMillis() / 1000L * 1000L;
    }

    static File spo2Dir(TestContext c) {
        return new File(c.dir, "shminterop-spo2");
    }

    static void answerCoverage() throws Exception {
        SpO2Adapter.resetForTest();
        Recorder l = new Recorder();
        SpO2Adapter.register(l, 49);
        startNight(l);
        // 100 s window: head zeros 0..9, value 96 at 10..19, hole 20..29, value 90 at 30..39, tail zeros.
        // Holes are answered as 0, never filled: SHM treats 0 as an invalid second.
        for (int i = 10; i < 20; i++) SpO2Adapter.store.put(T0 + i * 1000L, 96);
        for (int i = 30; i < 40; i++) SpO2Adapter.store.put(T0 + i * 1000L, 90);
        getData(l, T0, T0 + 99_000L);
        List<Bundle> ev = l.await(1);
        eq(1, ev.size(), "one chunk");
        int[] v = ev.get(0).getIntArray("secSpO2List");
        eq(100, v.length, "dense window");
        for (int i = 0; i < 10; i++) eq(0, v[i], "head second " + i + " stays 0");
        eq(96, v[19], "value before the hole");
        for (int i = 20; i < 30; i++) eq(0, v[i], "hole second " + i + " stays 0");
        eq(90, v[30], "value after the hole");
        for (int i = 40; i < 100; i++) eq(0, v[i], "tail second " + i + " stays 0");
        eq("coverage 20.0% (SHM needs 70%)", SpO2Adapter.lastCoverage, "coverage text");
        SpO2Adapter.unregister(l, 49);
    }

    static void resumeWindow() {
        try {
            for (long ageH : new long[] {3L, 7L}) {
                SpO2Adapter.contextOverride = new TestContext("resume-" + ageH);
                SpO2Adapter.resetForTest();
                Recorder a = new Recorder(), b = new Recorder();
                SpO2Adapter.register(a, 49);
                startNight(a);
                long t = nowSecond() - ageH * 3600_000L;
                SpO2Adapter.store.put(t, 95);
                SpO2Adapter.unregister(a, 49);
                SpO2Adapter.register(b, 49);
                startNight(b);
                eq(ageH < 6 ? 95 : 0, SpO2Adapter.store.get(t),
                        "second " + ageH + " h old after a new SET_OPERATION_MODE");
                SpO2Adapter.unregister(b, 49);
            }
        } finally {
            SpO2Adapter.contextOverride = null;
            SpO2Adapter.resetForTest();
        }
    }

    /** Simulates a process death mid-night: state reset, files kept. */
    static TestContext killedMidNight(String name, long newestAgeMs, boolean marker) {
        TestContext c = new TestContext(name);
        SpO2Adapter.contextOverride = c;
        SpO2Adapter.resetForTest();
        Recorder a = new Recorder();
        SpO2Adapter.register(a, 49);
        startNight(a);
        check(new File(spo2Dir(c), "recording").exists(), name + ": marker written on start");
        SpO2Adapter.store.put(nowSecond() - newestAgeMs, 96);
        SpO2Adapter.store.flush();
        SpO2Adapter.resetForTest(); // the "death": nothing is stopped, the marker stays
        if (!marker) new File(spo2Dir(c), "recording").delete();
        return c;
    }

    static void autoResume() throws Exception {
        try {
            // Fresh night and marker: the first register resumes and adopts the recorder role.
            TestContext c = killedMidNight("auto-fresh", 5 * 60_000L, true);
            check(!SpO2Adapter.isRecording(), "not recording right after the restart");
            Recorder l = new Recorder();
            SpO2Adapter.register(l, 49);
            check(SpO2Adapter.isRecording(), "auto-resumed with a fresh night and a marker");
            check(PowerManager.last != null && PowerManager.last.held, "wake lock held while recording");
            eq("shminterop:spo2", PowerManager.last.tag, "wake lock tag");
            eq(PowerManager.PARTIAL_WAKE_LOCK, PowerManager.last.level, "partial wake lock");
            check(!PowerManager.last.referenceCounted, "wake lock is not reference counted");
            eq(10_000L, PowerManager.last.timeoutMs, "wake lock held 10 s while the tracker connects");
            int before = PowerManager.last.acquires;
            feedPulses(3, 0.52);
            check(PowerManager.last.acquires > before, "each PPG batch holds the wake lock again");
            eq(1_000L, PowerManager.last.timeoutMs, "a PPG batch holds the wake lock for 1 s");
            check(getData(l, nowSecond() - 100_000L, nowSecond()), "GET_DATA still answered");
            check(l.await(1).size() == 1, "GET_DATA event delivered after auto-resume");
            SpO2Adapter.unregister(l, 49);
            check(!SpO2Adapter.isRecording(), "adopting listener's unregister stops the recording");
            check(!new File(spo2Dir(c), "recording").exists(), "marker deleted on stop");
            check(!PowerManager.last.held, "wake lock released on stop");

            // A version probe by the adopting listener gives the role up.
            killedMidNight("auto-probe", 60_000L, true);
            Recorder probe = new Recorder();
            SpO2Adapter.register(probe, 49);
            check(SpO2Adapter.isRecording(), "auto-resumed (probe case)");
            SpO2Adapter.setAttribute(probe, 49, attr(EnumSet.of(Cmd.GET_VERSION),
                    "versionType", SemSleepWatchSpO2SensorParam.VersionType.values()[0]));
            SpO2Adapter.unregister(probe, 49);
            check(SpO2Adapter.isRecording(), "a GET_VERSION listener does not stop an auto-resumed night");
            Recorder sleeper = new Recorder();
            SpO2Adapter.register(sleeper, 49);
            startNight(sleeper);
            SpO2Adapter.unregister(sleeper, 49);
            check(!SpO2Adapter.isRecording(), "the SET_OPERATION_MODE listener owns the recording");

            // Restart whose first call is the morning GET_DATA: answered, then nothing keeps recording.
            TestContext morning = killedMidNight("auto-morning", 60_000L, true);
            Recorder m = new Recorder();
            check(getData(m, nowSecond() - 100_000L, nowSecond()), "morning GET_DATA answered");
            check(m.await(1).size() == 1, "morning GET_DATA event delivered");
            check(!SpO2Adapter.isRecording(), "GET_DATA stops an unowned auto-resumed recording");
            check(!new File(spo2Dir(morning), "recording").exists(), "marker deleted after the morning GET_DATA");

            // Stale night: not resumed, marker removed.
            TestContext stale = killedMidNight("auto-stale", 45 * 60_000L, true);
            Recorder s = new Recorder();
            SpO2Adapter.register(s, 49);
            check(!SpO2Adapter.isRecording(), "stale night is not resumed");
            check(!new File(spo2Dir(stale), "recording").exists(), "stale marker deleted");

            // No marker: not resumed even with fresh data.
            killedMidNight("auto-nomarker", 60_000L, false);
            Recorder n = new Recorder();
            SpO2Adapter.register(n, 49);
            check(!SpO2Adapter.isRecording(), "no marker, no auto-resume");
        } finally {
            SpO2Adapter.contextOverride = null;
            SpO2Adapter.resetForTest();
        }
    }

    static void fallbackStart() {
        SpO2Adapter.resetForTest();
        Recorder silent = new Recorder(), probe = new Recorder(), gone = new Recorder(), data = new Recorder();
        SpO2Adapter.register(probe, 49);
        SpO2Adapter.setAttribute(probe, 49, attr(EnumSet.of(Cmd.GET_VERSION),
                "versionType", SemSleepWatchSpO2SensorParam.VersionType.values()[0]));
        SpO2Adapter.fallbackCheck(probe);
        check(!SpO2Adapter.isRecording(), "no fallback start for a listener that sent GET_VERSION");

        SpO2Adapter.register(gone, 49);
        SpO2Adapter.unregister(gone, 49);
        SpO2Adapter.fallbackCheck(gone);
        check(!SpO2Adapter.isRecording(), "no fallback start for a listener that unregistered");

        SpO2Adapter.register(data, 49);
        getData(data, T0, T0 + 5000L);
        SpO2Adapter.fallbackCheck(data);
        check(!SpO2Adapter.isRecording(), "no fallback start for a listener that sent GET_DATA");

        SpO2Adapter.register(silent, 49);
        SpO2Adapter.fallbackCheck(silent);
        check(SpO2Adapter.isRecording(), "fallback start for a listener that stays silent");
        SpO2Adapter.unregister(silent, 49);
        check(!SpO2Adapter.isRecording(), "the fallback listener owns the recording");
        SpO2Adapter.unregister(probe, 49);
        SpO2Adapter.unregister(data, 49);
    }

    static void feedPulses(int seconds, double r) {
        for (int i = 0; i < seconds * 100; i += 50) {
            SpO2Adapter.trackerListener().onBatch(pulses(i, 50, r));
        }
    }

    static void orientationFlip() throws Exception {
        TestContext c = new TestContext("orient-flip");
        SpO2Adapter.contextOverride = c;
        try {
            SpO2Adapter.resetForTest();
            Recorder l = new Recorder();
            SpO2Adapter.register(l, 49);
            startNight(l);
            check(SpO2Adapter.currentSwap() == SpO2Adapter.SWAP_RED_IR, "starts with the default orientation");
            // Wrong orientation: the adapter sees R about 1.75 for 140 s.
            feedPulses(140, 1.75);
            check(SpO2Adapter.currentSwap() != SpO2Adapter.SWAP_RED_IR, "orientation flipped");
            File f = new File(spo2Dir(c), "orientation");
            check(f.isFile(), "orientation persisted");
            eq("swap=" + !SpO2Adapter.SWAP_RED_IR, new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").trim(),
                    "persisted text");
            int expected = Spo2Estimator.fromR(1 / 1.75);
            int v = SpO2Adapter.store.get(T0 + 135_000L);
            check(Math.abs(v - expected) <= 1, "values after the flip use the new orientation: " + v + " ~ " + expected);
            SpO2Adapter.unregister(l, 49);

            // A later night starts from the persisted orientation.
            SpO2Adapter.resetForTest();
            Recorder m = new Recorder();
            SpO2Adapter.register(m, 49);
            startNight(m);
            check(SpO2Adapter.currentSwap() != SpO2Adapter.SWAP_RED_IR, "persisted orientation read back");
            SpO2Adapter.unregister(m, 49);
        } finally {
            SpO2Adapter.contextOverride = null;
            SpO2Adapter.resetForTest();
        }
    }

    static void orientationKept() throws Exception {
        TestContext c = new TestContext("orient-keep");
        SpO2Adapter.contextOverride = c;
        try {
            SpO2Adapter.resetForTest();
            Recorder l = new Recorder();
            SpO2Adapter.register(l, 49);
            startNight(l);
            feedPulses(140, 0.52);
            check(SpO2Adapter.currentSwap() == SpO2Adapter.SWAP_RED_IR, "R 0.52 keeps the orientation");
            check(!new File(spo2Dir(c), "orientation").exists(), "nothing persisted when kept");
            int expected = Spo2Estimator.fromR(0.52);
            int v = SpO2Adapter.store.get(T0 + 135_000L);
            check(Math.abs(v - expected) <= 1, "value " + v + " ~ " + expected);
            SpO2Adapter.unregister(l, 49);
        } finally {
            SpO2Adapter.contextOverride = null;
            SpO2Adapter.resetForTest();
        }
    }

    // ------------------------------------------------------------ night diagnostics (issue #13)

    static List<String> nightLines(List<String> sink, String prefix) {
        List<String> out = new java.util.ArrayList<String>();
        for (String l : sink) if (l.startsWith(prefix)) out.add(l);
        return out;
    }

    static void nightDiagnostics() throws Exception {
        TestContext c = new TestContext("night-diag");
        SpO2Adapter.contextOverride = c;
        List<String> sink = new CopyOnWriteArrayList<String>();
        SpO2Adapter.logSink = sink;
        try {
            // A recorded flow followed by GET_DATA.
            SpO2Adapter.resetForTest();
            Recorder l = new Recorder();
            SpO2Adapter.register(l, 49);
            startNight(l);
            SpO2Adapter.trackerListener().onArmed();
            feedPulses(140, 0.52);
            long now = nowSecond();
            check(getData(l, now - 3_600_000L, now), "GET_DATA returns true");
            List<String> summary = nightLines(sink, "night: ");
            List<String> events = nightLines(sink, "night-event: ");
            eq(1, summary.size(), "one night summary line");
            if (!summary.isEmpty()) {
                System.out.println("SAMPLE " + summary.get(0));
                check(summary.get(0).contains("recording=true") && summary.get(0).contains("coverage "),
                        "summary reports recording and coverage: " + summary.get(0));
            }
            String joined = String.join("\n", events);
            for (String l2 : events) System.out.println("SAMPLE " + l2);
            check(joined.contains("process start"), "journal has process start");
            check(joined.contains("recording start: new night"), "journal has the recording start");
            check(joined.contains("cmd SET_OPERATION_MODE"), "journal has SET_OPERATION_MODE");
            check(joined.contains("tracker armed"), "journal has tracker armed");
            check(new File(spo2Dir(c), "events.log").isFile(), "journal file written");
            SpO2Adapter.unregister(l, 49);
            check(SpO2Adapter.journal.since(0).toString().length() > 0
                    && SpO2Adapter.journal.size() >= 5, "journal holds entries after unregister");

            // GET_DATA with no recording at all.
            SpO2Adapter.resetForTest();
            sink.clear();
            Recorder m = new Recorder();
            SpO2Adapter.register(m, 49);
            check(getData(m, now - 3_600_000L, now), "idle GET_DATA returns true");
            summary = nightLines(sink, "night: ");
            eq(1, summary.size(), "idle: one night summary line");
            if (!summary.isEmpty()) {
                System.out.println("SAMPLE " + summary.get(0));
                check(summary.get(0).contains("recording=false") && summary.get(0).contains("coverage 0"),
                        "idle summary: recording=false, coverage 0: " + summary.get(0));
                check(summary.get(0).contains("stored 0 s"), "idle summary: nothing stored");
            }
            check(nightLines(sink, "night-event: ").size() >= 2, "idle: journal entries dumped");
            SpO2Adapter.unregister(m, 49);

            // With shared storage the journal goes to the external files dir the patcher reads.
            final File ext = new File(c.dir, "external");
            TestContext e = new TestContext("night-ext") {
                @Override public File getExternalFilesDir(String type) {
                    return ext;
                }
            };
            SpO2Adapter.contextOverride = e;
            SpO2Adapter.resetForTest();
            Recorder x = new Recorder();
            SpO2Adapter.register(x, 49);
            SpO2Adapter.unregister(x, 49);
            check(new File(new File(ext, "shminterop-spo2"), "events.log").isFile(),
                    "journal written to the external files dir");
            check(!new File(spo2Dir(e), "events.log").exists(), "no internal journal with shared storage");
        } finally {
            SpO2Adapter.logSink = null;
            SpO2Adapter.contextOverride = null;
            SpO2Adapter.resetForTest();
        }
    }
}
