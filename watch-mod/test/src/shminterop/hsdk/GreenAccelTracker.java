package shminterop.hsdk;

import android.content.Context;

/**
 * Host-test shadow of the real GreenAccelTracker (run-tests.sh only, never dexed): the real one
 * binds the Health Tracking service over Android binder types the host cannot run. It only
 * counts calls; the tests drive IhrnAdapter's tracker listener directly. If IhrnAdapter starts
 * calling more of the real tracker, this class fails to compile until it is extended.
 */
public class GreenAccelTracker {
    public interface Listener {
        void onArmed();
        void onGreen(PpgBatch batch);
        void onAccel(AccelBatch batch);
        void onError(String tracker, HealthError error);
        void onUnsupported(String tracker);
        void onConnectionFailed(String reason);
        void onConnectionEnded();
    }

    public static volatile int starts;
    public static volatile int stops;
    public static volatile int flushes;

    public GreenAccelTracker(Context context, Listener listener) {
    }

    public void start() { starts++; }

    public void stop() { stops++; }

    public void flush() { flushes++; }
}
