package shminterop.hsdk;

import java.util.Arrays;

/**
 * Plain main-based tests of the PPG_GREEN / ACCELEROMETER wire logic (protocol 4.5): the flush
 * request, the accelerometer value layout (x, y, z as integer fields), the PPG_GREEN layout with
 * only two values, and the callback dispatch. Exits non-zero on failure.
 */
public final class ServiceProtocolAccelTest {
    private static int failures;
    static final String CLS = ServiceProtocol.SERVICE_VALUE_CLASS;

    public static void main(String[] args) {
        constantsAndFlush();
        accelLayout();
        accelSkipsIncompletePoints();
        accelNullAndMalformed();
        greenTwoValues();
        dispatch();
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

    static void accelPoint(FakeWire w, boolean lengthField, long ts, int... values) {
        w.writeInt(1);
        w.writeLong(ts);
        w.writeInt(values.length);
        for (int v : values) ServiceProtocolPpgTest.value(w, lengthField, CLS, v, 0f, 0L);
    }

    static void constantsAndFlush() {
        eq("PPG_GREEN", ServiceProtocol.TRACKER_PPG_GREEN, "green tracker string");
        eq("ACCELEROMETER", ServiceProtocol.TRACKER_ACCELEROMETER, "accel tracker string");
        eq(4, ServiceProtocol.TX_FLUSH, "tx flush");
        FakeWire w = new FakeWire();
        ServiceProtocol.writeFlushRequest(w, "com.app", ServiceProtocol.TRACKER_ACCELEROMETER);
        eq(Arrays.asList("TOKEN:" + ServiceProtocol.SERVICE_DESCRIPTOR, "String:com.app",
                "String:ACCELEROMETER"), w.dump(), "code 4 request");
    }

    static void accelLayout() {
        for (int i = 0; i < 2; i++) {
            boolean lf = i == 0;
            String what = lf ? "len accel" : "nolen accel";
            FakeWire w = new FakeWire();
            w.writeInt(2);
            accelPoint(w, lf, 5000L, 12, -4095, 300);
            accelPoint(w, lf, 5040L, -1, 2, -3);
            AccelBatch b = ServiceProtocol.parseAccelBatch(w, lf);
            check(b != null, what + " not null");
            if (b == null) continue;
            eq(2, b.samples.length, what + " count");
            eq(5000L, b.samples[0].timestampMs, what + " ts");
            eq(12, b.samples[0].x, what + " x");
            eq(-4095, b.samples[0].y, what + " y");
            eq(300, b.samples[0].z, what + " z");
            eq(5040L, b.samples[1].timestampMs, what + " ts[1]");
            eq(-3, b.samples[1].z, what + " z[1]");
        }
    }

    static void accelSkipsIncompletePoints() {
        FakeWire w = new FakeWire();
        w.writeInt(3);
        accelPoint(w, true, 1L, 1, 2);          // only two axes
        accelPoint(w, true, 2L, 1, 2, 3, 4);    // extra values are ignored
        w.writeInt(0);                          // null element
        AccelBatch b = ServiceProtocol.parseAccelBatch(w, true);
        eq(1, b.samples.length, "incomplete point skipped");
        eq(2L, b.samples[0].timestampMs, "kept point");
    }

    static void accelNullAndMalformed() {
        FakeWire w = new FakeWire();
        w.writeInt(-1);
        check(ServiceProtocol.parseAccelBatch(w, true) == null, "null list");
        w = new FakeWire();
        w.writeInt(0);
        check(ServiceProtocol.parseAccelBatch(w, true) == null, "empty list");
        w = new FakeWire();
        w.writeInt(1_000_000);
        try { ServiceProtocol.parseAccelBatch(w, true); check(false, "huge n"); }
        catch (WireFormatException expected) { check(true, "huge n rejected"); }
        w = new FakeWire();
        w.writeInt(2);
        accelPoint(w, true, 1L, 1, 2, 3);
        try { ServiceProtocol.parseAccelBatch(w, true); check(false, "truncated"); }
        catch (WireFormatException expected) { check(true, "truncated rejected"); }
    }

    static void greenTwoValues() {
        // PPG_GREEN delivers [green, status] only; red/IR default to "no data".
        FakeWire w = new FakeWire();
        w.writeInt(2);
        accelPoint(w, true, 100L, 2180000, 0);
        accelPoint(w, true, 140L, 0, -999);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        eq(2, b.samples.length, "green count");
        eq(2180000, b.samples[0].greenAdc, "green adc");
        check(b.samples[0].isGreenNormal(), "status 0 is normal");
        eq(-999, b.samples[1].greenStatus, "error status kept");
        check(!b.samples[1].isGreenNormal(), "-999 not normal");
        check(!b.samples[0].isRedNormal(), "no red");
    }

    static void dispatch() {
        final int[] counts = new int[3];
        ServiceProtocol.AccelSink sink = new ServiceProtocol.AccelSink() {
            @Override public void onBatch(AccelBatch batch) { counts[0] += batch.samples.length; }
            @Override public void onError(HealthError error) { counts[1]++; }
            @Override public void onMalformed(String reason) { counts[2]++; }
        };
        FakeWire w = new FakeWire();
        w.writeInt(1);
        accelPoint(w, true, 7L, 1, 2, 3);
        ServiceProtocol.handleAccelEvent(ServiceProtocol.CB_DATA, w, true, sink);
        eq(1, counts[0], "data dispatched");
        w = new FakeWire();
        w.writeInt(0);
        ServiceProtocol.handleAccelEvent(ServiceProtocol.CB_ERROR, w, true, sink);
        eq(1, counts[1], "error dispatched");
        w = new FakeWire();
        w.writeInt(1_000_000);
        ServiceProtocol.handleAccelEvent(ServiceProtocol.CB_DATA, w, true, sink);
        eq(1, counts[2], "malformed dispatched");
        ServiceProtocol.handleAccelEvent(ServiceProtocol.CB_FLUSH, new FakeWire(), true, sink);
        eq(1, counts[0], "flush ignored");
    }
}
