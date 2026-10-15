package shminterop.hsdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Plain main-based tests of the PPG_ON_DEMAND wire logic (docs/health-tracking-protocol.md 4.4):
 * the code 8 request encoding, the six-position value layout the service produces (green ADC count
 * in the integer field of position 0, channel status in position 1) and the callback dispatch.
 * Exits non-zero on failure.
 */
public final class ServiceProtocolPpgTest {
    private static int failures;

    static final String CLS = ServiceProtocol.SERVICE_VALUE_CLASS;

    public static void main(String[] args) {
        constants();
        setPpgTypeRequest();
        batchLayout();
        statusMapping();
        redIrChannels();
        multiPoint();
        nullAndEmptyLists();
        classMismatch();
        malformed();
        handlePpgEventDispatch();
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

    /** One ServiceValue element; the float and long fields are unused for PPG. */
    static void value(FakeWire w, boolean lengthField, String cls, int i, float f, long l) {
        w.writeInt(4);
        int lenIdx = -1, start = 0;
        if (lengthField) {
            lenIdx = w.nextIndex();
            w.writeInt(0);
            start = w.size();
        }
        w.writeString(cls);
        w.writeInt(i);
        w.writeFloat(f);
        w.writeLong(l);
        if (lengthField) w.patchInt(lenIdx, w.size() - start);
    }

    /** A data point with the six values the producer always writes (4.4), in the fixed order. */
    static void point(FakeWire w, boolean lengthField, long ts,
                      int greenAdc, int greenStatus, int redAdc, int redStatus,
                      int irAdc, int irStatus) {
        w.writeInt(1);
        w.writeLong(ts);
        w.writeInt(6);
        value(w, lengthField, CLS, greenAdc, 0f, 0L);
        value(w, lengthField, CLS, greenStatus, 0f, 0L);
        value(w, lengthField, CLS, redAdc, 0f, 0L);
        value(w, lengthField, CLS, redStatus, 0f, 0L);
        value(w, lengthField, CLS, irAdc, 0f, 0L);
        value(w, lengthField, CLS, irStatus, 0f, 0L);
    }

    /**
     * One-point batch whose six positions carry pairwise different values and whose unused
     * float/long fields are nonzero. Position 0's float is ECG's mV-unavailable sentinel: for PPG
     * it must be ignored, the ADC count lives in the integer field.
     */
    static FakeWire distinctBatch(boolean lengthField) {
        FakeWire w = new FakeWire();
        w.writeInt(1);  // one data point
        w.writeInt(1);  // non-null element
        w.writeLong(7000L);
        w.writeInt(6);
        value(w, lengthField, CLS, 111, -9999.0f, 5L); // pos 0: green ADC count
        value(w, lengthField, CLS, 0, 1f, 6L);         // pos 1: green status (normal)
        value(w, lengthField, CLS, 222, 2f, 7L);       // pos 2: red ADC count
        value(w, lengthField, CLS, -1, 3f, 8L);        // pos 3: red status (error)
        value(w, lengthField, CLS, 333, 4f, 9L);       // pos 4: IR ADC count
        value(w, lengthField, CLS, -1, 5f, 10L);       // pos 5: IR status (error)
        return w;
    }

    // ---------------------------------------------------------------- tests

    static void constants() {
        eq(8, ServiceProtocol.TX_SET_PPG_TYPE, "tx setPpgType");
        eq("PPG_ON_DEMAND", ServiceProtocol.TRACKER_PPG_ON_DEMAND, "tracker type string");
        eq("GREEN", ServiceProtocol.PPG_TYPE_GREEN, "green channel string");
        eq("RED", ServiceProtocol.PPG_TYPE_RED, "red channel string");
        eq("IR", ServiceProtocol.PPG_TYPE_IR, "IR channel string");
        eq(0, PpgBatch.STATUS_NORMAL, "status normal");
        eq(-1, PpgBatch.STATUS_ERROR, "status error");
    }

    static void setPpgTypeRequest() {
        String T = "TOKEN:" + ServiceProtocol.SERVICE_DESCRIPTOR;
        FakeWire w = new FakeWire();
        ServiceProtocol.writeSetPpgTypeRequest(w, "com.app", ServiceProtocol.TRACKER_PPG_ON_DEMAND,
                Collections.singletonList(ServiceProtocol.PPG_TYPE_GREEN));
        eq(Arrays.asList(T, "String:com.app", "String:PPG_ON_DEMAND", "Integer:1", "String:GREEN"),
                w.dump(), "code 8 request");

        w = new FakeWire();
        ServiceProtocol.writeSetPpgTypeRequest(w, "com.app", ServiceProtocol.TRACKER_PPG_ON_DEMAND,
                Collections.<String>emptyList());
        eq(Arrays.asList(T, "String:com.app", "String:PPG_ON_DEMAND", "Integer:0"),
                w.dump(), "code 8 request, empty list");

        w = new FakeWire();
        ServiceProtocol.writeSetPpgTypeRequest(w, "com.app", ServiceProtocol.TRACKER_PPG_ON_DEMAND,
                null);
        eq(Arrays.asList(T, "String:com.app", "String:PPG_ON_DEMAND", "Integer:-1"),
                w.dump(), "code 8 request, null list");

        // The request list encoding is the createStringArrayList layout the service Stub reads:
        // int count (-1 = null) then the strings; symmetric with readStringList.
        w = new FakeWire();
        ServiceProtocol.writeStringList(w, Arrays.asList("GREEN", "RED", "IR"));
        eq(Arrays.asList("GREEN", "RED", "IR"), ServiceProtocol.readStringList(w), "string list round trip");
    }

    static void batchLayout() {
        for (int i = 0; i < 2; i++) {
            boolean lengthField = i == 0;
            String what = lengthField ? "len ppg layout" : "nolen ppg layout";
            PpgBatch b = ServiceProtocol.parsePpgBatch(distinctBatch(lengthField), lengthField);
            check(b != null, what + " not null");
            if (b == null) continue;
            // One data point per sample and per delivery on the wire (4.4).
            eq(1, b.samples.length, what + " count");
            eq(7000L, b.samples[0].timestampMs, what + " timestamp");
            // The green ADC count is the integer field of position 0: not the red (222) or IR
            // (333) counts, and present despite the -9999.0 float, which only means something
            // for ECG.
            eq(111, b.samples[0].greenAdc, what + " green from position 0");
            eq(0, b.samples[0].greenStatus, what + " status from position 1");
            check(b.samples[0].isGreenNormal(), what + " isGreenNormal");
            // Red/IR: ADC counts from positions 2 and 4, statuses from 3 and 5 (here both error).
            eq(222, b.samples[0].redAdc, what + " red from position 2");
            eq(-1, b.samples[0].redStatus, what + " red status from position 3");
            eq(333, b.samples[0].irAdc, what + " IR from position 4");
            eq(-1, b.samples[0].irStatus, what + " IR status from position 5");
            check(!b.samples[0].isRedNormal() && !b.samples[0].isIrNormal(), what + " red/IR not normal");
        }
    }

    static void statusMapping() {
        // green status -1: error, never reported as normal.
        FakeWire w = new FakeWire();
        w.writeInt(1);
        point(w, true, 10L, 500, -1, 0, -1, 0, -1);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "error status count");
        eq(PpgBatch.STATUS_ERROR, b.samples[0].greenStatus, "error status value");
        check(!b.samples[0].isGreenNormal(), "error status not normal");

        // An absent status position defaults to the producer default (-1): no guarantee.
        w = new FakeWire();
        w.writeInt(1);  // one data point
        w.writeInt(1);  // non-null element
        w.writeLong(9L);
        w.writeInt(1);
        value(w, true, CLS, 500, 0f, 0L);
        b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "absent status count");
        eq(PpgBatch.STATUS_ERROR, b.samples[0].greenStatus, "absent status defaults to error");

        // A null channel travels as count 0 / status -1 and the point is still delivered (the
        // producer always writes six values): kept, flagged not normal.
        w = new FakeWire();
        w.writeInt(1);
        point(w, true, 11L, 0, -1, 0, -1, 0, -1);
        b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "null-channel point kept");
        eq(0, b.samples[0].greenAdc, "null-channel adc");
        check(!b.samples[0].isGreenNormal(), "null-channel not normal");
    }

    static void redIrChannels() {
        // A SpO2 request (RED, IR only): green is null (0 / -1), red and IR carry data.
        FakeWire w = new FakeWire();
        w.writeInt(2);
        point(w, true, 100L, 0, -1, 250000, 0, 380000, 0);
        point(w, true, 110L, 0, -1, 250100, 0, 380050, -1);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        eq(2, b.samples.length, "red/ir count");
        eq(250000, b.samples[0].redAdc, "red adc");
        eq(380000, b.samples[0].irAdc, "ir adc");
        check(b.samples[0].isRedNormal() && b.samples[0].isIrNormal(), "red/ir normal");
        check(!b.samples[0].isGreenNormal(), "green absent");
        check(b.samples[1].isRedNormal() && !b.samples[1].isIrNormal(), "per-channel status");

        // Only positions 0 and 1 present: red/IR default to 0 / error (no guarantee).
        w = new FakeWire();
        w.writeInt(1);
        w.writeInt(1);
        w.writeLong(9L);
        w.writeInt(2);
        value(w, true, CLS, 500, 0f, 0L);
        value(w, true, CLS, 0, 0f, 0L);
        b = ServiceProtocol.parsePpgBatch(w, true);
        eq(0, b.samples[0].redAdc, "absent red adc");
        eq(PpgBatch.STATUS_ERROR, b.samples[0].redStatus, "absent red status");
        eq(PpgBatch.STATUS_ERROR, b.samples[0].irStatus, "absent IR status");
        // The three-argument constructor keeps the green-only behaviour (BpAdapter tests).
        PpgSample g = new PpgSample(1L, 5, 0);
        check(g.isGreenNormal() && !g.isRedNormal() && !g.isIrNormal(), "green-only sample");
    }

    static void multiPoint() {
        FakeWire w = new FakeWire();
        w.writeInt(3);
        point(w, true, 100L, 10, 0, 0, -1, 0, -1);
        point(w, true, 200L, 20, 0, 0, -1, 0, -1);
        point(w, true, 300L, 30, -1, 0, -1, 0, -1);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        check(b != null, "multi not null");
        if (b == null) return;
        eq(3, b.samples.length, "multi count");
        eq(100L, b.samples[0].timestampMs, "multi ts[0]");
        eq(200L, b.samples[1].timestampMs, "multi ts[1]");
        eq(300L, b.samples[2].timestampMs, "multi ts[2]");
        eq(10, b.samples[0].greenAdc, "multi adc[0]");
        eq(20, b.samples[1].greenAdc, "multi adc[1]");
        eq(30, b.samples[2].greenAdc, "multi adc[2]");
        check(b.samples[0].isGreenNormal() && b.samples[1].isGreenNormal(), "multi normal[0..1]");
        check(!b.samples[2].isGreenNormal(), "multi abnormal[2]");
    }

    static void nullAndEmptyLists() {
        FakeWire w = new FakeWire();
        w.writeInt(-1);
        check(ServiceProtocol.parsePpgBatch(w, true) == null, "null list");
        w = new FakeWire();
        w.writeInt(0);
        check(ServiceProtocol.parsePpgBatch(w, true) == null, "empty list");

        // Null elements and null value lists are skipped.
        w = new FakeWire();
        w.writeInt(3);
        w.writeInt(0);                                   // null element
        w.writeInt(1); w.writeLong(1L); w.writeInt(-1);  // null value list
        point(w, true, 5L, 7, 0, 0, -1, 0, -1);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "skipped null entries");
        eq(7, b.samples[0].greenAdc, "skipped null entries adc");

        // A point without any value (m = 0) carries no green sample and is dropped.
        w = new FakeWire();
        w.writeInt(2);
        w.writeInt(1); w.writeLong(1L); w.writeInt(0);
        point(w, true, 6L, 8, 0, 0, -1, 0, -1);
        b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "empty point skipped");

        // All points unusable: null batch, not an error.
        w = new FakeWire();
        w.writeInt(1);
        w.writeInt(0);
        check(ServiceProtocol.parsePpgBatch(w, true) == null, "all points unusable");
    }

    static void classMismatch() {
        // With the length field the unknown element is skipped and the point (no pos 0) dropped.
        FakeWire w = new FakeWire();
        w.writeInt(2);
        w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        value(w, true, "some.Other", 1, 2f, 0L);
        point(w, true, 2L, 9, 0, 0, -1, 0, -1);
        PpgBatch b = ServiceProtocol.parsePpgBatch(w, true);
        eq(1, b.samples.length, "mismatched element skipped");
        eq(9, b.samples[0].greenAdc, "following point still parsed");

        // Without the length field a mismatch cannot be skipped: rejected.
        w = new FakeWire();
        w.writeInt(1);
        w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        value(w, false, "some.Other", 1, 2f, 0L);
        try {
            ServiceProtocol.parsePpgBatch(w, false);
            check(false, "mismatch without length must be rejected");
        } catch (WireFormatException expected) {
            check(true, "rejected");
        }

        // A length field wrongly assumed (writer had none): the class name is not found -> rejected.
        try {
            ServiceProtocol.parsePpgBatch(distinctBatch(false), true);
            check(false, "wrong length assumption must be rejected");
        } catch (WireFormatException expected) {
            check(true, "rejected 2");
        } catch (IllegalStateException fake) {
            check(true, "rejected by fake type check");
        }
    }

    static void malformed() {
        FakeWire w = new FakeWire();
        w.writeInt(1_000_000);
        try { ServiceProtocol.parsePpgBatch(w, true); check(false, "huge n"); }
        catch (WireFormatException expected) { check(true, "huge n rejected"); }

        w = new FakeWire();
        w.writeInt(1);
        try { ServiceProtocol.parsePpgBatch(w, true); check(false, "truncated"); }
        catch (WireFormatException expected) { check(true, "truncated rejected"); }

        w = new FakeWire();
        w.writeInt(1); w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        w.writeInt(7); // not a Parcelable tag
        try { ServiceProtocol.parsePpgBatch(w, true); check(false, "bad tag"); }
        catch (WireFormatException expected) { check(true, "bad tag rejected"); }

        w = new FakeWire();
        w.writeInt(1); w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        w.writeInt(4);
        w.writeInt(1_000_000); // impossible length prefix
        w.writeString(CLS); w.writeInt(0); w.writeFloat(0f); w.writeLong(0L);
        try { ServiceProtocol.parsePpgBatch(w, true); check(false, "bad length"); }
        catch (WireFormatException expected) { check(true, "bad length rejected"); }

        w = new FakeWire();
        w.writeInt(1); w.writeInt(1); w.writeLong(1L);
        w.writeInt(100); // far beyond the six values the producer writes
        try { ServiceProtocol.parsePpgBatch(w, true); check(false, "huge m"); }
        catch (WireFormatException expected) { check(true, "huge m rejected"); }
    }

    static void handlePpgEventDispatch() {
        final List<String> log = new ArrayList<String>();
        ServiceProtocol.PpgSink sink = new ServiceProtocol.PpgSink() {
            @Override public void onBatch(PpgBatch b) { log.add("batch:" + b.samples.length); }
            @Override public void onError(HealthError e) { log.add("error:" + e); }
            @Override public void onMalformed(String r) { log.add("malformed"); }
        };
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_DATA, distinctBatch(true), true, sink);
        FakeWire e = new FakeWire(); e.writeInt(0);
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_ERROR, e, true, sink);
        e = new FakeWire(); e.writeInt(1);
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_ERROR, e, true, sink);
        FakeWire bad = new FakeWire(); bad.writeInt(1_000_000);
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_DATA, bad, true, sink);
        // onFlushCompleted (code 3) is never sent for PPG_ON_DEMAND: flush is not allowed (3.4).
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_FLUSH, new FakeWire(), true, sink);
        // Anything unknown is ignored, not an error.
        ServiceProtocol.handlePpgEvent(7, new FakeWire(), true, sink);
        // A null list delivers nothing (neither a batch nor a malformed report).
        FakeWire nul = new FakeWire(); nul.writeInt(-1);
        ServiceProtocol.handlePpgEvent(ServiceProtocol.CB_DATA, nul, true, sink);
        eq(Arrays.asList("batch:1", "error:PERMISSION_ERROR", "error:SDK_POLICY_ERROR", "malformed"),
                log, "ppg dispatch log");
    }
}
