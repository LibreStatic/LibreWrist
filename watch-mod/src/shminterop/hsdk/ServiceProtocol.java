package shminterop.hsdk;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java encoding and parsing of the Health Tracking Service binder protocol
 * (docs/health-tracking-protocol.md). No Android types: everything goes through
 * {@link WireReader}/{@link WireWriter} so host tests can drive it.
 */
public final class ServiceProtocol {
    private ServiceProtocol() {}

    public static final String SERVICE_PACKAGE = "com.samsung.android.service.health";
    public static final String SERVICE_ACTION =
            "com.samsung.android.service.health.wear.sts.ACTION_SERVICE";
    /** Descriptor of the interface implemented by the service. */
    public static final String SERVICE_DESCRIPTOR =
            "com.samsung.android.service.health.tracking.IClientEventListener";
    /** Descriptor of the callback interface implemented by this client. */
    public static final String LISTENER_DESCRIPTOR =
            "com.samsung.android.service.health.tracking.IServiceEventListener";
    public static final String SERVICE_VALUE_CLASS =
            "com.samsung.android.service.health.tracking.data.ServiceValue";

    public static final String TRACKER_ECG = "ECG";

    // service transactions
    public static final int TX_CREATE = 1;
    public static final int TX_SET_LISTENER = 2;
    public static final int TX_UNSET_LISTENER = 3;
    public static final int TX_SUPPORTED_TYPES = 5;
    // listener transactions
    public static final int CB_DATA = 1;
    public static final int CB_ERROR = 2;
    public static final int CB_FLUSH = 3;

    static final int VAL_PARCELABLE = 4;
    static final int MAX_POINTS = 100000;
    static final int MAX_VALUES = 64;
    static final float MV_UNAVAILABLE = -9999.0f;

    /** Receives the decoded listener callbacks. */
    public interface EventSink {
        void onBatch(EcgBatch batch);
        void onError(HealthError error);
        /** The batch did not match the documented layout and was dropped. */
        void onMalformed(String reason);
    }

    // ------------------------------------------------------------- requests

    /** Code 5: no arguments after the interface token. */
    public static void writeSupportedTypesRequest(WireWriter w) {
        w.writeInterfaceToken(SERVICE_DESCRIPTOR);
    }

    /** Code 1 for a tracker without user profile and exercise type. */
    public static void writeCreateRequest(WireWriter w, String appId, String trackerType) {
        w.writeInterfaceToken(SERVICE_DESCRIPTOR);
        w.writeString(appId);
        w.writeString(trackerType);
        w.writeInt(0);        // TrackerUserProfile: null marker
        w.writeString(null);  // exerciseType
    }

    /** Code 2. */
    public static void writeSetListenerRequest(WireWriter w, String appId, String trackerType,
                                               Object listenerBinder) {
        w.writeInterfaceToken(SERVICE_DESCRIPTOR);
        w.writeString(appId);
        w.writeString(trackerType);
        w.writeStrongBinder(listenerBinder);
    }

    /** Code 3. */
    public static void writeUnsetListenerRequest(WireWriter w, String appId, String trackerType) {
        w.writeInterfaceToken(SERVICE_DESCRIPTOR);
        w.writeString(appId);
        w.writeString(trackerType);
    }

    /** Reply of code 5 (after the exception header): int count (-1 = null) then the strings. */
    public static List<String> readStringList(WireReader r) {
        int n = r.readInt();
        List<String> out = new ArrayList<String>();
        if (n < 0) return out;
        if (n > MAX_POINTS) throw new WireFormatException("string list too long: " + n);
        for (int i = 0; i < n; i++) out.add(r.readString());
        return out;
    }

    // ------------------------------------------------------------- callbacks

    /**
     * Decodes one listener transaction (the interface token is already consumed).
     * Never throws for malformed data; reports it through {@link EventSink#onMalformed}.
     *
     * @param hasLengthField true when the writer runs on API 33+ (length int after the Parcelable tag)
     */
    public static void handleEvent(int code, WireReader data, boolean hasLengthField, EventSink sink) {
        switch (code) {
            case CB_DATA:
                EcgBatch batch;
                try {
                    batch = parseBatch(data, hasLengthField);
                } catch (WireFormatException e) {
                    sink.onMalformed(e.getMessage());
                    return;
                }
                if (batch != null) sink.onBatch(batch);
                break;
            case CB_ERROR:
                sink.onError(HealthError.fromWireCode(data.readInt()));
                break;
            default: // CB_FLUSH is never sent for ECG; ignore anything unknown
                break;
        }
    }

    /**
     * Parses an {@code onDataUpdated} payload (protocol 4.1/4.2).
     *
     * @return the batch, or null when the list is null (-1), empty, or has no usable sample
     * @throws WireFormatException when the layout cannot be followed
     */
    public static EcgBatch parseBatch(WireReader r, boolean hasLengthField) {
        int n = r.readInt();
        if (n <= 0) return null;
        if (n > MAX_POINTS) throw new WireFormatException("too many data points: " + n);

        List<EcgSample> samples = new ArrayList<EcgSample>(n);
        int lead = EcgBatch.ABSENT;
        int seq = EcgBatch.ABSENT;
        float maxTh = Float.NaN;
        float minTh = Float.NaN;

        for (int i = 0; i < n; i++) {
            if (r.dataAvail() <= 0) throw new WireFormatException("truncated at data point " + i);
            if (r.readInt() == 0) continue; // null element
            long ts = r.readLong();
            int m = r.readInt();
            if (m < 0) continue;            // null value list
            if (m > MAX_VALUES) throw new WireFormatException("too many values: " + m);

            boolean[] ok = new boolean[m];
            int[] ints = new int[m];
            float[] floats = new float[m];
            for (int k = 0; k < m; k++) {
                int tag = r.readInt();
                if (tag != VAL_PARCELABLE) {
                    // Other value types have no length prefix: cannot be skipped.
                    throw new WireFormatException("unexpected value tag " + tag);
                }
                int end = -1;
                if (hasLengthField) {
                    int len = r.readInt();
                    if (len < 0 || len > r.dataAvail()) {
                        throw new WireFormatException("bad value length " + len);
                    }
                    end = r.dataPosition() + len;
                }
                String cls = r.readString();
                if (!SERVICE_VALUE_CLASS.equals(cls)) {
                    if (end < 0) throw new WireFormatException("unexpected value class " + cls);
                    r.setDataPosition(end); // skip the unknown element
                    continue;
                }
                ints[k] = r.readInt();
                floats[k] = r.readFloat();
                r.readLong();
                ok[k] = true;
                if (end >= 0) r.setDataPosition(end);
            }

            if (m == 0 || !ok[0]) continue; // no mV for this point
            float mv = floats[0];
            samples.add(new EcgSample(ts, mv == MV_UNAVAILABLE ? Float.NaN : mv));

            if (i == 0) { // per-batch fields live only on the first data point
                if (m > 2 && ok[2]) lead = ints[2];
                if (m > 3 && ok[3]) seq = ints[3];
                if (m > 4 && ok[4] && !(ints[4] == -1 && floats[4] == -1.0f)) maxTh = floats[4];
                if (m > 5 && ok[5] && !(ints[5] == -1 && floats[5] == -1.0f)) minTh = floats[5];
            }
        }
        if (samples.isEmpty()) return null;
        return new EcgBatch(samples.toArray(new EcgSample[0]), lead, seq, maxTh, minTh);
    }
}
