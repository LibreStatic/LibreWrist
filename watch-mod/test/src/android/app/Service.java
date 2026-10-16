package android.app;

/** Host-test double of the framework Service (run-tests.sh only, never dexed). */
public class Service {
    public int foregroundCalls;

    public void startForeground(int id, Notification notification) {
        foregroundCalls++;
    }
}
