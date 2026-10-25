package android.os;

/**
 * Host-test double of the framework PowerManager (run-tests.sh only, never dexed): records the
 * wake locks that were created so the tests can inspect their state.
 */
public class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;

    /** The most recently created lock. */
    public static WakeLock last;

    public WakeLock newWakeLock(int levelAndFlags, String tag) {
        last = new WakeLock(levelAndFlags, tag);
        return last;
    }

    public static class WakeLock {
        public final int level;
        public final String tag;
        public boolean referenceCounted = true;
        public boolean held;
        public long timeoutMs;
        public int acquires;

        WakeLock(int level, String tag) {
            this.level = level;
            this.tag = tag;
        }

        public void setReferenceCounted(boolean value) {
            referenceCounted = value;
        }

        public void acquire(long timeout) {
            held = true;
            timeoutMs = timeout;
            acquires++;
        }

        public void release() {
            held = false;
        }

        public boolean isHeld() {
            return held;
        }
    }
}
