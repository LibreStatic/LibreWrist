package android.os;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Host-test double of the framework Bundle (run-tests.sh only, never dexed): a typed map with the
 * put/get surface the adapters and their tests use. The device build compiles against the real
 * android.jar; parcel semantics are out of scope here.
 */
public class Bundle {
    private final Map<String, Object> map = new HashMap<String, Object>();

    public void putInt(String key, int value) { map.put(key, value); }
    public int getInt(String key) { Object v = map.get(key); return v instanceof Integer ? (Integer) v : 0; }

    public void putLong(String key, long value) { map.put(key, value); }
    public long getLong(String key) { Object v = map.get(key); return v instanceof Long ? (Long) v : 0L; }

    public void putSerializable(String key, Serializable value) { map.put(key, value); }
    public Serializable getSerializable(String key) { return (Serializable) map.get(key); }

    public void putIntArray(String key, int[] value) { map.put(key, value); }
    public int[] getIntArray(String key) { return (int[]) map.get(key); }

    public void putLongArray(String key, long[] value) { map.put(key, value); }
    public long[] getLongArray(String key) { return (long[]) map.get(key); }

    public void putShortArray(String key, short[] value) { map.put(key, value); }
    public short[] getShortArray(String key) { return (short[]) map.get(key); }

    public void putBooleanArray(String key, boolean[] value) { map.put(key, value); }
    public boolean[] getBooleanArray(String key) { return (boolean[]) map.get(key); }

    public boolean containsKey(String key) { return map.containsKey(key); }
}
