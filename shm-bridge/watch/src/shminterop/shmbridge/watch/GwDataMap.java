package shminterop.shmbridge.watch;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Minimal encoder of the GMS Wearable DataMap: produces the exact bytes that
 * {@code DataMap.fromByteArray} (obfuscated class {@code u} of heartplugin) expects to parse,
 * so that GwHub's synthetic DataItems (battery, memory, state) are consumed by the stock
 * phone-side WearableListenerService.
 *
 * <p>Format verified in the smali of heartplugin 2.2.12:
 * {@code u.A()} serializes via {@code f0.c} to a protobuf
 * {@code DataMapProto { repeated Entry entry = 1 }} with
 * {@code Entry { string key = 1; Value value = 2 }} and
 * {@code Value { uint32 tag = 1; Payload payload = 2 }}, where the payload
 * ({@code wearable_oem/a}) has one field per Java type. Keys are sorted
 * (TreeSet, natural order) as {@code f0.c} does. Tag table of {@code f0.p}:</p>
 *
 * <pre>
 *   1 = byte[]  → payload field 1 (bytes)   2 = String → field 2 (string)
 *   3 = Double  → field 3 (double)          4 = Float  → field 4 (float)
 *   5 = Long    → field 5 (int64 varint)    6 = Integer → field 6 (int32 varint)
 *   7 = Byte    → field 7 (sint32 [C])      8 = Boolean → field 8 (bool)
 *   9 = nested DataMap · 10 = ArrayList (only String/Integer/Long/DataMap/null)
 *   13 = Asset (out-of-band index) · 14 = null (Value without payload)
 * </pre>
 *
 * <p>Phase 2: scalars only (tags 1-6 and 8), which cover battery/memory/state. Nested,
 * ArrayList, Asset and arrays are left out (documented stub; large blobs → existing
 * {@code /__bridge__} channel). The fields not emitted (7, 9-14) are not needed for
 * the target flows.</p>
 *
 * <p>Pure Java, no Android: tested on the host JVM with
 * {@code shm-bridge/watch/test/run-tests.sh}.</p>
 */
public final class GwDataMap {

    /** Value proto tags (only those this encoder emits). */
    static final int TAG_BYTES = 1, TAG_STRING = 2, TAG_DOUBLE = 3, TAG_FLOAT = 4,
            TAG_LONG = 5, TAG_INT = 6, TAG_BOOL = 8;

    private final TreeMap<String, Object> values = new TreeMap<>();

    public GwDataMap putString(String key, String v) { return put(TAG_STRING, key, v); }
    public GwDataMap putInt(String key, int v) { return put(TAG_INT, key, v); }
    public GwDataMap putLong(String key, long v) { return put(TAG_LONG, key, v); }
    public GwDataMap putBoolean(String key, boolean v) { return put(TAG_BOOL, key, v); }
    public GwDataMap putDouble(String key, double v) { return put(TAG_DOUBLE, key, v); }
    public GwDataMap putFloat(String key, float v) { return put(TAG_FLOAT, key, v); }
    public GwDataMap putByteArray(String key, byte[] v) { return put(TAG_BYTES, key, v); }

    private GwDataMap put(int tag, String key, Object v) {
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("empty key");
        if (v == null) throw new IllegalArgumentException("null value (use tag 14 without payload)");
        values.put(key, new Object[]{tag, v});
        return this;
    }

    public int size() { return values.size(); }

    /** Serializes: entries in key order, each one {@code 0x0A len(entry)}, entry =
     *  {@code 0x0A len(key) key 0x12 len(value) value}, value = {@code 0x08 tag 0x12 len payload}. */
    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            Object[] tv = (Object[]) e.getValue();
            int tag = (Integer) tv[0];
            byte[] payload = payload(tag, tv[1]);
            byte[] key = e.getKey().getBytes(StandardCharsets.UTF_8);
            // value = tag (field 1, varint) + payload (field 2, bytes)
            int vlen = varintSize(tag) + varintSize(payload.length) + payload.length + 2;
            int elen = varintSize(key.length) + key.length + varintSize(vlen) + vlen + 2;
            out.write(0x0A);                    // field 1 (repeated Entry), wire 2
            writeVarint(out, elen);
            out.write(0x0A);                    // Entry.key, field 1, wire 2
            writeVarint(out, key.length);
            out.write(key, 0, key.length);
            out.write(0x12);                    // Entry.value, field 2, wire 2
            writeVarint(out, vlen);
            out.write(0x08);                    // Value.tag, field 1, wire 0
            writeVarint(out, tag);
            out.write(0x12);                    // Value.payload, field 2, wire 2
            writeVarint(out, payload.length);
            out.write(payload, 0, payload.length);
        }
        return out.toByteArray();
    }

    /** The payload is the complete message {@code a}: each type with its field header. */
    private static byte[] payload(int tag, Object v) {
        switch (tag) {
            case TAG_STRING: {
                byte[] s = ((String) v).getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream o = new ByteArrayOutputStream(8 + s.length);
                o.write(0x12);                                  // field 2, wire 2
                writeVarint(o, s.length);
                o.write(s, 0, s.length);
                return o.toByteArray();
            }
            case TAG_INT: return field(0x30, varint((Integer) v));   // field 6, wire 0 (int32)
            case TAG_LONG: return field(0x28, varint((Long) v));     // field 5, wire 0 (int64)
            case TAG_BOOL: return field(0x40, new byte[]{(Boolean) v ? (byte) 1 : (byte) 0});
            case TAG_DOUBLE: return field(0x19, le64(Double.doubleToRawLongBits((Double) v)));
            case TAG_FLOAT: return field(0x25, le32(Float.floatToRawIntBits((Float) v)));
            case TAG_BYTES: {
                byte[] b = (byte[]) v;
                ByteArrayOutputStream o = new ByteArrayOutputStream(8 + b.length);
                o.write(0x0A);                                  // field 1, wire 2
                writeVarint(o, b.length);
                o.write(b, 0, b.length);
                return o.toByteArray();
            }
            default: throw new IllegalArgumentException("tag no soportado en fase 2: " + tag);
        }
    }

    private static byte[] field(int marker, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(1 + content.length);
        o.write(marker);
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ decoder

    /** Parses DataMap bytes into Java values (Long/Integer/String/Boolean/Double/Float/byte[]);
     *  unsupported keys (tag 7/9-14) get a null value. Tolerant of unknown fields:
     *  it skips them by wire type. For host tests and diagnostics. */
    public static Map<String, Object> decode(byte[] bytes) {
        Map<String, Object> out = new LinkedHashMap<>();
        Cursor c = new Cursor(bytes);
        while (c.hasMore()) {
            long[] fh = c.fieldHeader();
            if (fh[0] != 1 || fh[1] != 2) { c.skip((int) fh[1]); continue; }   // we expect Entry
            byte[] entry = c.bytes();
            String key = null;
            byte[] valueBytes = null;
            Cursor ec = new Cursor(entry);
            while (ec.hasMore()) {
                long[] eh = ec.fieldHeader();
                if (eh[1] != 2) { ec.skip((int) eh[1]); continue; }
                if (eh[0] == 1) key = new String(ec.bytes(), StandardCharsets.UTF_8);
                else if (eh[0] == 2) valueBytes = ec.bytes();
                else ec.skip(2);
            }
            if (key == null || valueBytes == null) continue;
            out.put(key, decodeValue(valueBytes));
        }
        return out;
    }

    private static Object decodeValue(byte[] b) {
        int tag = 0;
        byte[] payload = null;
        Cursor vc = new Cursor(b);
        while (vc.hasMore()) {
            long[] h = vc.fieldHeader();
            if (h[1] == 0 && h[0] == 1) tag = (int) vc.varint();
            else if (h[1] == 2 && h[0] == 2) payload = vc.bytes();
            else vc.skip((int) h[1]);
        }
        if (payload == null) return null;   // tag 14 (null) or malformed
        Cursor pc = new Cursor(payload);
        while (pc.hasMore()) {
            long[] h = pc.fieldHeader();
            if (h[0] == 1 && h[1] == 2) return pc.bytes();
            if (h[0] == 2 && h[1] == 2) return new String(pc.bytes(), StandardCharsets.UTF_8);
            if (h[0] == 3 && h[1] == 1) return Double.longBitsToDouble(pc.fixed64());
            if (h[0] == 4 && h[1] == 5) return Float.intBitsToFloat((int) pc.fixed32());
            if (h[0] == 5 && h[1] == 0) return pc.varint();               // int64
            if (h[0] == 6 && h[1] == 0) return (int) pc.varint();         // int32
            if (h[0] == 8 && h[1] == 0) return pc.varint() != 0;          // bool
            pc.skip((int) h[1]);
        }
        return null;
    }

    // ------------------------------------------------------------------ proto primitives

    private static final class Cursor {
        private final byte[] b;
        private int pos;

        Cursor(byte[] b) { this.b = b == null ? new byte[0] : b; }

        boolean hasMore() { return pos < b.length; }

        /** [field number, wire type] of the current header. */
        long[] fieldHeader() {
            long key = varint();
            return new long[]{key >>> 3, key & 7};
        }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                if (pos >= b.length) throw new IllegalArgumentException("varint truncado");
                byte x = b[pos++];
                v |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new IllegalArgumentException("varint demasiado largo");
            }
        }

        byte[] bytes() {
            int len = (int) varint();
            if (pos + len > b.length) throw new IllegalArgumentException("bytes truncados");
            byte[] out = new byte[len];
            System.arraycopy(b, pos, out, 0, len);
            pos += len;
            return out;
        }

        long fixed64() {
            if (pos + 8 > b.length) throw new IllegalArgumentException("fixed64 truncado");
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (b[pos + i] & 0xff);
            pos += 8;
            return v;
        }

        long fixed32() {
            if (pos + 4 > b.length) throw new IllegalArgumentException("fixed32 truncado");
            int v = ((b[pos] & 0xff)) | ((b[pos + 1] & 0xff) << 8)
                    | ((b[pos + 2] & 0xff) << 16) | ((b[pos + 3] & 0xff) << 24);
            pos += 4;
            return v & 0xFFFFFFFFL;
        }

        /** Skips a field by wire type (unknown fields: forward-safe). */
        void skip(int wireType) {
            switch (wireType) {
                case 0: varint(); break;
                case 1: fixed64(); break;
                case 2: bytes(); break;
                case 5: fixed32(); break;
                default: throw new IllegalArgumentException("wire type no saltable: " + wireType);
            }
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int v) {
        writeVarint(out, v & 0xFFFFFFFFL);
    }

    private static void writeVarint(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static int varintSize(long v) {
        int n = 1;
        while ((v & ~0x7fL) != 0) { v >>>= 7; n++; }
        return n;
    }

    private static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(10);
        writeVarint(o, v);
        return o.toByteArray();
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] le64(long v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24),
                (byte) (v >>> 32), (byte) (v >>> 40), (byte) (v >>> 48), (byte) (v >>> 56)};
    }

    private static final class ByteArrayOutputStream {
        private byte[] buf = new byte[64];
        private int len;

        ByteArrayOutputStream(int cap) { if (cap > 64) buf = new byte[cap]; }

        void write(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        void write(byte[] src, int off, int n) {
            ensure(n);
            System.arraycopy(src, off, buf, len, n);
            len += n;
        }

        private void ensure(int n) {
            if (len + n > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, len + n)];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
        }

        byte[] toByteArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }
}
