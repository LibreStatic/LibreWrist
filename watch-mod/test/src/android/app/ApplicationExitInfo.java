package android.app;

/** Host-test double of the framework ApplicationExitInfo (run-tests.sh only, never dexed). */
public class ApplicationExitInfo {
    public static final int REASON_EXIT_SELF = 1;
    public static final int REASON_SIGNALED = 2;
    public static final int REASON_LOW_MEMORY = 3;
    public static final int REASON_CRASH = 4;
    public static final int REASON_CRASH_NATIVE = 5;
    public static final int REASON_ANR = 6;
    public static final int REASON_INITIALIZATION_FAILURE = 7;
    public static final int REASON_PERMISSION_CHANGE = 8;
    public static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
    public static final int REASON_USER_REQUESTED = 10;
    public static final int REASON_USER_STOPPED = 11;
    public static final int REASON_DEPENDENCY_DIED = 12;
    public static final int REASON_OTHER = 13;
    public static final int REASON_FREEZER = 14;
    public static final int REASON_PACKAGE_STATE_CHANGE = 15;
    public static final int REASON_PACKAGE_UPDATED = 16;

    private final int reason;
    private final String description;
    private final long timestamp;
    private final int importance;
    private final long pss;
    private final long rss;

    public ApplicationExitInfo(int reason, String description, long timestamp, int importance,
            long pss, long rss) {
        this.reason = reason;
        this.description = description;
        this.timestamp = timestamp;
        this.importance = importance;
        this.pss = pss;
        this.rss = rss;
    }

    public int getReason() { return reason; }
    public String getDescription() { return description; }
    public long getTimestamp() { return timestamp; }
    public int getImportance() { return importance; }
    public long getPss() { return pss; }
    public long getRss() { return rss; }
}
