package android.app;

import java.util.List;

/** Host-test double of the framework ActivityManager (run-tests.sh only, never dexed). */
public class ActivityManager {
    /** What getHistoricalProcessExitReasons returns; a test sets it, or {@link #failure}. */
    public List<ApplicationExitInfo> exits;
    public RuntimeException failure;
    public String lastPackage;

    public List<ApplicationExitInfo> getHistoricalProcessExitReasons(String packageName, int pid,
            int maxNum) {
        lastPackage = packageName;
        if (failure != null) throw failure;
        return exits;
    }
}
