package shminterop.hsdk;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ECG tracker of the Health Tracking Service: connect, capability check, create, register the
 * listener, deliver batches and errors; re-arms after a service restart (protocol 5 and 7).
 *
 * {@link Listener} callbacks may run on binder or main threads and must return quickly.
 */
public final class EcgTracker {
    private static final String TAG = "EcgTracker";

    public interface Listener {
        /** Registered with the service; data (or an error) follows. */
        void onArmed();
        void onBatch(EcgBatch batch);
        void onError(HealthError error);
        /** The watch does not offer ECG through the service. */
        void onUnsupported();
        void onConnectionFailed(String reason);
        void onConnectionEnded();
    }

    private final HealthClient client;
    private final Listener out;
    private final ListenerBinder binder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public EcgTracker(Context context, Listener listener) {
        this.out = listener;
        this.binder = new ListenerBinder(new ServiceProtocol.EventSink() {
            @Override public void onBatch(EcgBatch batch) { out.onBatch(batch); }
            @Override public void onError(HealthError error) { out.onError(error); }
            @Override public void onMalformed(String reason) { Log.w(TAG, "dropped batch: " + reason); }
        });
        this.client = new HealthClient(context, new HealthClient.Listener() {
            @Override public void onConnected(HealthClient c) { arm(); }
            @Override public void onConnectionFailed(String reason) { out.onConnectionFailed(reason); }
            @Override public void onServiceLost() { Log.w(TAG, "service lost; waiting for restart"); }
            @Override public void onConnectionEnded() { out.onConnectionEnded(); }
        });
    }

    public void start() {
        running = true;
        client.connect();
    }

    /** Code 3, then unbind. */
    public void stop() {
        running = false;
        if (client.isConnected()) {
            try { client.unsetEventListener(ServiceProtocol.TRACKER_ECG); } catch (Throwable t) {
                Log.w(TAG, "unset failed: " + t);
            }
        }
        client.disconnect();
        worker.shutdownNow();
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
            if (types == null || !types.contains(ServiceProtocol.TRACKER_ECG)) {
                out.onUnsupported();
                return;
            }
            client.createTracker(ServiceProtocol.TRACKER_ECG);
            // No lock is held here: PERMISSION_ERROR may call back on this thread.
            client.setEventListener(ServiceProtocol.TRACKER_ECG, binder);
            if (!running) {
                client.unsetEventListener(ServiceProtocol.TRACKER_ECG);
                return;
            }
            out.onArmed();
        } catch (RemoteException e) {
            out.onConnectionFailed("transaction failed: " + e);
        } catch (RuntimeException e) {
            out.onConnectionFailed("transaction failed: " + e);
        }
    }
}
