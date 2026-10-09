package shminterop.hsdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain main-based tests of the wire logic; exits non-zero on failure. */
public final class ServiceProtocolTest {
    private static int failures;

    static final String CLS = ServiceProtocol.SERVICE_VALUE_CLASS;

    public static void main(String[] args) {
        requestEncoding();
        errorMapping();
        batchWithLengthField();
        batchWithoutLengthField();
        perBatchFieldsOnlyFromIndexZero();
        unavailableMv();
        nullAndEmptyLists();
        classMismatch();
        malformed();
        handleEventDispatch();
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

    /** One ServiceValue element. */
    static void value(FakeWire w, boolean lengthField, String cls, int i, float f) {
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
        w.writeLong(0L);
        if (lengthField) w.patchInt(lenIdx, w.size() - start);
    }

    /** A point with the six ECG values; pass null for "-1 everywhere" in positions 1..5. */
    static void point(FakeWire w, boolean lengthField, long ts, float mv, int[] meta, float[] thr) {
        w.writeInt(1);
        w.writeLong(ts);
        w.writeInt(6);
        value(w, lengthField, CLS, 1234, mv);
        value(w, lengthField, CLS, meta == null ? -1 : meta[0], 0f); // ppg
        value(w, lengthField, CLS, meta == null ? -1 : meta[1], 0f); // lead
        value(w, lengthField, CLS, meta == null ? -1 : meta[2], 0f); // seq
        value(w, lengthField, CLS, meta == null ? -1 : 77, thr == null ? -1f : thr[0]);
        value(w, lengthField, CLS, meta == null ? -1 : 78, thr == null ? -1f : thr[1]);
    }

    static FakeWire batch(boolean lengthField, int n, float mvBase, int lead, int seq) {
        FakeWire w = new FakeWire();
        w.writeInt(n);
        for (int i = 0; i < n; i++) {
            if (i == 0) point(w, lengthField, 1000L, mvBase, new int[]{500, lead, seq}, new float[]{1.5f, -1.5f});
            else point(w, lengthField, 1000L, mvBase + i, null, null);
        }
        return w;
    }

    // ---------------------------------------------------------------- tests

    static void requestEncoding() {
        String T = "TOKEN:" + ServiceProtocol.SERVICE_DESCRIPTOR;
        FakeWire w = new FakeWire();
        ServiceProtocol.writeSupportedTypesRequest(w);
        eq(Arrays.asList(T), w.dump(), "code 5 request");

        w = new FakeWire();
        ServiceProtocol.writeCreateRequest(w, "com.app", "ECG");
        eq(Arrays.asList(T, "String:com.app", "String:ECG", "Integer:0", "String:null"), w.dump(), "code 1 request");

        w = new FakeWire();
        ServiceProtocol.writeSetListenerRequest(w, "com.app", "ECG", "B");
        eq(Arrays.asList(T, "String:com.app", "String:ECG", "BINDER:B"), w.dump(), "code 2 request");

        w = new FakeWire();
        ServiceProtocol.writeUnsetListenerRequest(w, "com.app", "ECG");
        eq(Arrays.asList(T, "String:com.app", "String:ECG"), w.dump(), "code 3 request");

        eq(1, ServiceProtocol.TX_CREATE, "tx create");
        eq(2, ServiceProtocol.TX_SET_LISTENER, "tx set");
        eq(3, ServiceProtocol.TX_UNSET_LISTENER, "tx unset");
        eq(5, ServiceProtocol.TX_SUPPORTED_TYPES, "tx caps");

        w = new FakeWire();
        w.writeInt(2); w.writeString("HEART_RATE"); w.writeString("ECG");
        eq(Arrays.asList("HEART_RATE", "ECG"), ServiceProtocol.readStringList(w), "string list");
        w = new FakeWire();
        w.writeInt(-1);
        eq(new ArrayList<String>(), ServiceProtocol.readStringList(w), "null string list");
    }

    static void errorMapping() {
        eq(HealthError.PERMISSION_ERROR, HealthError.fromWireCode(0), "code 0");
        eq(HealthError.SDK_POLICY_ERROR, HealthError.fromWireCode(1), "code 1");
        eq(HealthError.SDK_POLICY_ERROR, HealthError.fromWireCode(7), "code 7");
        eq(HealthError.SDK_POLICY_ERROR, HealthError.fromWireCode(-1), "code -1");
    }

    static void assertBatch(EcgBatch b, int n, String what) {
        check(b != null, what + " not null");
        if (b == null) return;
        eq(n, b.samples.length, what + " count");
        eq(1000L, b.samples[0].timestampMs, what + " timestamp");
        eq(0.5f, b.samples[0].mv, what + " mv[0]");
        eq(1.5f, n > 1 ? b.samples[1].mv : 1.5f, what + " mv[1]");
        eq(5, b.leadOff, what + " lead");
        eq(42, b.sequence, what + " seq");
        eq(1.5f, b.maxThresholdMv, what + " max");
        eq(-1.5f, b.minThresholdMv, what + " min");
    }

    static void batchWithLengthField() {
        assertBatch(ServiceProtocol.parseBatch(batch(true, 10, 0.5f, 5, 42), true), 10, "len batch");
    }

    static void batchWithoutLengthField() {
        assertBatch(ServiceProtocol.parseBatch(batch(false, 10, 0.5f, 5, 42), false), 10, "nolen batch");
    }

    static void perBatchFieldsOnlyFromIndexZero() {
        // index 0 has no per-batch fields, a later point carries bogus ones: ignored.
        FakeWire w = new FakeWire();
        w.writeInt(2);
        point(w, true, 1L, 0.1f, null, null);
        point(w, true, 1L, 0.2f, new int[]{9, 5, 99}, new float[]{3f, 4f});
        EcgBatch b = ServiceProtocol.parseBatch(w, true);
        check(b != null, "idx0 batch");
        eq(2, b.samples.length, "idx0 count");
        eq(EcgBatch.ABSENT, b.leadOff, "lead absent when index 0 has -1");
        eq(EcgBatch.ABSENT, b.sequence, "seq absent");
        check(Float.isNaN(b.maxThresholdMv) && Float.isNaN(b.minThresholdMv), "thresholds absent (-1/-1.0)");

        // normal: lead 0 at index 0 and -1 on the last point stays 0
        b = ServiceProtocol.parseBatch(batch(true, 10, 0.5f, 0, 3), true);
        eq(0, b.leadOff, "lead 0 from index 0, not from the last point");
        eq(3, b.sequence, "seq from index 0");
    }

    static void unavailableMv() {
        FakeWire w = new FakeWire();
        w.writeInt(2);
        point(w, true, 5L, -9999.0f, new int[]{1, 0, 1}, new float[]{0f, 0f});
        point(w, true, 5L, 0.25f, null, null);
        EcgBatch b = ServiceProtocol.parseBatch(w, true);
        check(!b.samples[0].hasMv() && Float.isNaN(b.samples[0].mv), "-9999.0 mV is unavailable");
        check(b.samples[1].hasMv(), "normal mV is available");
        eq(0.25f, b.samples[1].mv, "mv value");
    }

    static void nullAndEmptyLists() {
        FakeWire w = new FakeWire();
        w.writeInt(-1);
        check(ServiceProtocol.parseBatch(w, true) == null, "null list");
        w = new FakeWire();
        w.writeInt(0);
        check(ServiceProtocol.parseBatch(w, true) == null, "empty list");
        // null element and null value list are skipped
        w = new FakeWire();
        w.writeInt(3);
        w.writeInt(0);                        // null element
        w.writeInt(1); w.writeLong(1L); w.writeInt(-1); // null values
        point(w, true, 1L, 0.3f, null, null);
        EcgBatch b = ServiceProtocol.parseBatch(w, true);
        eq(1, b.samples.length, "skipped null entries");
    }

    static void classMismatch() {
        // With the length field the unknown element is skipped and the point (no pos 0) dropped.
        FakeWire w = new FakeWire();
        w.writeInt(2);
        w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        value(w, true, "some.Other", 1, 2f);
        point(w, true, 2L, 0.7f, new int[]{1, 0, 1}, new float[]{0f, 0f});
        EcgBatch b = ServiceProtocol.parseBatch(w, true);
        eq(1, b.samples.length, "mismatched element skipped");
        eq(0.7f, b.samples[0].mv, "following point still parsed");

        // Without the length field a mismatch cannot be skipped: rejected.
        w = new FakeWire();
        w.writeInt(1);
        w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        value(w, false, "some.Other", 1, 2f);
        try {
            ServiceProtocol.parseBatch(w, false);
            check(false, "mismatch without length must be rejected");
        } catch (WireFormatException expected) {
            check(true, "rejected");
        }

        // A length field wrongly assumed (writer had none): the class name is not found -> rejected
        try {
            ServiceProtocol.parseBatch(batch(false, 3, 0.5f, 5, 1), true);
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
        try { ServiceProtocol.parseBatch(w, true); check(false, "huge n"); }
        catch (WireFormatException expected) { check(true, "huge n rejected"); }

        w = new FakeWire();
        w.writeInt(1); w.writeInt(1); w.writeLong(1L); w.writeInt(1);
        w.writeInt(7); // not a Parcelable tag
        try { ServiceProtocol.parseBatch(w, true); check(false, "bad tag"); }
        catch (WireFormatException expected) { check(true, "bad tag rejected"); }
    }

    static void handleEventDispatch() {
        final List<String> log = new ArrayList<String>();
        ServiceProtocol.EventSink sink = new ServiceProtocol.EventSink() {
            @Override public void onBatch(EcgBatch b) { log.add("batch:" + b.samples.length); }
            @Override public void onError(HealthError e) { log.add("error:" + e); }
            @Override public void onMalformed(String r) { log.add("malformed"); }
        };
        ServiceProtocol.handleEvent(ServiceProtocol.CB_DATA, batch(true, 10, 0.5f, 5, 42), true, sink);
        FakeWire e = new FakeWire(); e.writeInt(0);
        ServiceProtocol.handleEvent(ServiceProtocol.CB_ERROR, e, true, sink);
        e = new FakeWire(); e.writeInt(1);
        ServiceProtocol.handleEvent(ServiceProtocol.CB_ERROR, e, true, sink);
        FakeWire bad = new FakeWire(); bad.writeInt(1_000_000);
        ServiceProtocol.handleEvent(ServiceProtocol.CB_DATA, bad, true, sink);
        ServiceProtocol.handleEvent(ServiceProtocol.CB_FLUSH, new FakeWire(), true, sink);
        FakeWire nul = new FakeWire(); nul.writeInt(-1);
        ServiceProtocol.handleEvent(ServiceProtocol.CB_DATA, nul, true, sink);
        eq(Arrays.asList("batch:10", "error:PERMISSION_ERROR", "error:SDK_POLICY_ERROR", "malformed"),
                log, "dispatch log");
    }
}
