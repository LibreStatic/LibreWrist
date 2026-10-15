package android.util;

/** Host-test double of the framework Log (run-tests.sh only, never dexed): prints to stdout. */
public class Log {
    public static int i(String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.out.println(tag + ": " + msg);
        return 0;
    }
}
