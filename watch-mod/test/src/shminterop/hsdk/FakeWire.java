package shminterop.hsdk;

import java.util.ArrayList;
import java.util.List;

/** In-memory parcel that mimics Parcel sizes (4-byte aligned) so data positions behave. */
final class FakeWire implements WireReader, WireWriter {
    private final List<Object> items = new ArrayList<Object>();
    private final List<Integer> offsets = new ArrayList<Integer>();
    private int size;      // bytes written
    private int readIdx;   // index of next item to read

    private void add(Object o, int bytes) {
        items.add(o);
        offsets.add(size);
        size += bytes;
    }

    private static int stringBytes(String s) {
        return 4 + (((s.length() + 1) * 2 + 3) & ~3);
    }

    // ---- writer
    @Override public void writeInterfaceToken(String d) { add("TOKEN:" + d, stringBytes(d) + 4); }
    @Override public void writeInt(int v) { add(Integer.valueOf(v), 4); }
    public void writeLong(long v) { add(Long.valueOf(v), 8); }
    public void writeFloat(float v) { add(Float.valueOf(v), 4); }
    @Override public void writeString(String v) { add(v == null ? (Object) NULL_STRING : v, v == null ? 4 : stringBytes(v)); }
    @Override public void writeStrongBinder(Object b) { add("BINDER:" + b, 8); }

    private static final Object NULL_STRING = new Object() { @Override public String toString() { return "null"; } };

    /** Bytes written so far (for patching length prefixes). */
    int size() { return size; }
    /** Index of the next item to be written. */
    int nextIndex() { return items.size(); }
    void patchInt(int index, int value) { items.set(index, Integer.valueOf(value)); }

    /** Human-readable item list, for asserting the request encoding order. */
    List<String> dump() {
        List<String> out = new ArrayList<String>();
        for (Object o : items) out.add(o == NULL_STRING ? "String:null"
                : o instanceof String ? (((String) o).startsWith("TOKEN:") || ((String) o).startsWith("BINDER:")
                        ? (String) o : "String:" + o)
                : o.getClass().getSimpleName() + ":" + o);
        return out;
    }

    // ---- reader
    private Object next(Class<?> type) {
        if (readIdx >= items.size()) throw new IllegalStateException("read past end");
        Object o = items.get(readIdx);
        if (!type.isInstance(o) && !(type == String.class && o == NULL_STRING)) {
            throw new IllegalStateException("expected " + type.getSimpleName() + " at item " + readIdx + " but found " + o);
        }
        readIdx++;
        return o;
    }
    @Override public int readInt() { return ((Integer) next(Integer.class)).intValue(); }
    @Override public long readLong() { return ((Long) next(Long.class)).longValue(); }
    @Override public float readFloat() { return ((Float) next(Float.class)).floatValue(); }
    @Override public String readString() { Object o = next(String.class); return o == NULL_STRING ? null : (String) o; }
    @Override public int dataPosition() { return readIdx < offsets.size() ? offsets.get(readIdx) : size; }
    @Override public void setDataPosition(int position) {
        for (int i = 0; i < offsets.size(); i++) if (offsets.get(i) == position) { readIdx = i; return; }
        if (position == size) { readIdx = items.size(); return; }
        throw new IllegalStateException("bad position " + position);
    }
    @Override public int dataAvail() { return size - dataPosition(); }
}
