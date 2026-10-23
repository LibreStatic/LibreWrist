package android.content.res;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-test double of the framework Resources (run-tests.sh only, never dexed): string lookup by
 * name, filled by the tests through {@link #STRINGS} ("package:name" -> text).
 */
public class Resources {
    public static final Map<String, String> STRINGS = new HashMap<String, String>();
    private static final Map<Integer, String> BY_ID = new HashMap<Integer, String>();

    public int getIdentifier(String name, String defType, String defPackage) {
        String text = STRINGS.get(defPackage + ":" + name);
        if (text == null || !"string".equals(defType)) return 0;
        int id = 0x7f100000 + Math.floorMod((defPackage + ":" + name).hashCode(), 0xffff);
        BY_ID.put(id, text);
        return id;
    }

    /** Package name of the resource table, as getResourcePackageName reports it for any id. */
    public static String tablePackage = "shminterop.test";

    public String getResourcePackageName(int id) {
        return tablePackage;
    }

    public String getString(int id) {
        String text = BY_ID.get(id);
        if (text == null) throw new IllegalArgumentException("no string " + id);
        return text;
    }
}
