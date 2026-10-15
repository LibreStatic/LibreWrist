package shminterop.hsdk;

import android.content.Context;

/**
 * Host-test shadow of the real PpgTracker (run-tests.sh only, never dexed): the real one binds
 * the Health Tracking service over Android binder types (Parcel, IBinder, Looper) the host can
 * neither compile nor run. Only the surface BpAdapter uses is declared; the tests drive
 * BpAdapter's own listener directly, so no behaviour lives here. If BpAdapter starts calling
 * more of the real tracker, this class fails to compile until it is extended.
 */
public class PpgTracker {
    public interface Listener {
        /** Registered with the service; data (or an error) follows. */
        void onArmed();
        void onBatch(PpgBatch batch);
        void onError(HealthError error);
        /** The watch does not offer PPG through the service. */
        void onUnsupported();
        void onConnectionFailed(String reason);
        void onConnectionEnded();
    }

    public PpgTracker(Context context, Listener listener) {
    }

    public PpgTracker(Context context, java.util.List<String> channels, Listener listener) {
    }

    public void start() {
    }

    public void stop() {
    }
}
