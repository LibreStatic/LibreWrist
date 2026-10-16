package shminterop.hsdk;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PPG_GREEN and ACCELEROMETER trackers of the Health Tracking Service on one connection: connect,
 * capability check, create both, register one listener per tracker, deliver batches and errors;
 * re-arms after a service restart. Both trackers read the same sensor-hub stream, so their
 * samples pair by timestamp. The hub batches (up to 300 samples), so {@link #flush} asks it to
 * deliver what it holds; it is allowed for both types.
 *
 * These are tracker types separate from PPG_ON_DEMAND (PpgTracker): the service keeps one entry
 * per appId and tracker type, and {@link #stop} unsets only the two types of this class.
 *
 * {@link Listener} callbacks may run on binder or main threads and must return quickly.
 */
public final class GreenAccelTracker {
    private static final String TAG = "GreenAccelTracker";

    public interface Listener {
        /** Both trackers registered with the service; data (or an error) follows. */
        void onArmed();
        void onGreen(PpgBatch batch);
        void onAccel(AccelBatch batch);
        /** [tracker] is the tracker type string the error came from. */
        void onError(String tracker, HealthError error);
        /** The watch does not offer this tracker type through the service. */
        void onUnsupported(String tracker);
        void onConnectionFailed(String reason);
        void onConnectionEnded();
    }

    private final HealthClient client;
    private final Listener out;
    private final ListenerBinder greenBinder;
    private final ListenerBinder accelBinder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    private volatile boolean armed;

    public GreenAccelTracker(Context context, Listener listener) {
        this.out = listener;
        this.greenBinder = new ListenerBinder(new ServiceProtocol.PpgSink() {
            @Override public void onBatch(PpgBatch batch) { out.onGreen(batch); }
            @Override public void onError(HealthError error) {
                out.onError(ServiceProtocol.TRACKER_PPG_GREEN, error);
            }
            @Override public void onMalformed(String reason) { Log.w(TAG, "dropped green batch: " + reason); }
        });
        this.accelBinder = new ListenerBinder(new ServiceProtocol.AccelSink() {
            @Override public void onBatch(AccelBatch batch) { out.onAccel(batch); }
            @Override public void onError(HealthError error) {
                out.onError(ServiceProtocol.TRACKER_ACCELEROMETER, error);
            }
            @Override public void onMalformed(String reason) { Log.w(TAG, "dropped accel batch: " + reason); }
        });
        this.client = new HealthClient(context, new HealthClient.Listener() {
            @Override public void onConnected(HealthClient c) { arm(); }
            @Override public void onConnectionFailed(String reason) { armed = false; out.onConnectionFailed(reason); }
            @Override public void onServiceLost() {
                armed = false;
                Log.w(TAG, "service lost; waiting for restart");
            }
            @Override public void onConnectionEnded() { armed = false; out.onConnectionEnded(); }
        });
    }

    public void start() {
        running = true;
        client.connect();
    }

    /** Code 3 for both types (never for PPG_ON_DEMAND), then unbind. */
    public void stop() {
        running = false;
        armed = false;
        if (client.isConnected()) {
            unset(ServiceProtocol.TRACKER_PPG_GREEN);
            unset(ServiceProtocol.TRACKER_ACCELEROMETER);
        }
        client.disconnect();
        worker.shutdownNow();
    }

    /**
     * Asks the service to deliver the batched samples of both trackers (code 4). Asynchronous and
     * safe from any thread; a no-op until both trackers are armed and after {@link #stop}.
     */
    public void flush() {
        if (!running || !armed) return;
        try {
            worker.execute(new Runnable() {
                @Override public void run() { doFlush(); }
            });
        } catch (RuntimeException rejected) {
            // stopped meanwhile
        }
    }

    private void unset(String type) {
        try {
            client.unsetEventListener(type);
        } catch (Throwable t) {
            Log.w(TAG, "unset " + type + " failed: " + t);
        }
    }

    private void doFlush() {
        if (!running || !armed) return;
        try {
            client.flush(ServiceProtocol.TRACKER_PPG_GREEN);
            client.flush(ServiceProtocol.TRACKER_ACCELEROMETER);
        } catch (RemoteException e) {
            Log.w(TAG, "flush failed: " + e);
        } catch (RuntimeException e) {
            Log.w(TAG, "flush failed: " + e);
        }
    }

    private void arm() {
        try {
            worker.execute(new Runnable() {
                @Override public void run() { doArm(); }
            });
        } catch (RuntimeException rejected) {
            // stopped meanwhile
        }
    }

    private void doArm() {
        if (!running) return;
        try {
            List<String> types = client.getSupportedTrackerTypes();
            if (types == null || !types.contains(ServiceProtocol.TRACKER_PPG_GREEN)) {
                out.onUnsupported(ServiceProtocol.TRACKER_PPG_GREEN);
                return;
            }
            if (!types.contains(ServiceProtocol.TRACKER_ACCELEROMETER)) {
                out.onUnsupported(ServiceProtocol.TRACKER_ACCELEROMETER);
                return;
            }
            client.createTracker(ServiceProtocol.TRACKER_PPG_GREEN);
            client.createTracker(ServiceProtocol.TRACKER_ACCELEROMETER);
            // No lock is held here: PERMISSION_ERROR may call back on this thread.
            client.setEventListener(ServiceProtocol.TRACKER_PPG_GREEN, greenBinder);
            client.setEventListener(ServiceProtocol.TRACKER_ACCELEROMETER, accelBinder);
            if (!running) {
                unset(ServiceProtocol.TRACKER_PPG_GREEN);
                unset(ServiceProtocol.TRACKER_ACCELEROMETER);
                return;
            }
            armed = true;
            out.onArmed();
        } catch (RemoteException e) {
            out.onConnectionFailed("transaction failed: " + e);
        } catch (RuntimeException e) {
            out.onConnectionFailed("transaction failed: " + e);
        }
    }
}
