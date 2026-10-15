package android.os;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-test double of the framework Bundle (run-tests.sh only, never dexed): just the int-map
 * surface BpAdapter and its test use. The device build compiles against the real android.jar;
 * parcel semantics are out of scope here.
 */
public class Bundle {
    private final Map<String, Integer> ints = new HashMap<String, Integer>();

    public void putInt(String key, int value) {
        ints.put(key, value);
    }

    public int getInt(String key) {
        Integer value = ints.get(key);
        return value == null ? 0 : value;
    }
}
