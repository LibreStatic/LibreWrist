package android.app;

/** Host-test double of the framework Service (run-tests.sh only, never dexed). */
public class Service extends android.content.Context {
    public int foregroundCalls;
    public int lastForegroundId;

    public void startForeground(int id, Notification notification) {
        foregroundCalls++;
        lastForegroundId = id;
    }
}
