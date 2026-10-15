package android.os;

/** Host-test double of the framework SystemClock (run-tests.sh only, never dexed). */
public class SystemClock {
    public static long elapsedRealtime() {
        return System.nanoTime() / 1_000_000L;
    }
}
