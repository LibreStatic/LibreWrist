package shminterop.hsdk;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PPG tracker of the Health Tracking Service: connect, capability check, create, select the LED
 * channels, register the listener, deliver batches and errors; re-arms after a service restart.
 *
 * {@link Listener} callbacks may run on binder or main threads and must return quickly.
 */
public final class PpgTracker {
    private static final String TAG = "PpgTracker";

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

    private final HealthClient client;
    private final Listener out;
    private final List<String> channels;
    private final ListenerBinder binder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    /** Green-only tracker (BP). */
    public PpgTracker(Context context, Listener listener) {
        this(context, Collections.singletonList(ServiceProtocol.PPG_TYPE_GREEN), listener);
    }

    /** Tracker for the given LED channels (ServiceProtocol.PPG_TYPE_*), e.g. RED and IR for SpO2. */
    public PpgTracker(Context context, List<String> channels, Listener listener) {
        this.out = listener;
        this.channels = new ArrayList<String>(channels);
        this.binder = new ListenerBinder(new ServiceProtocol.PpgSink() {
            @Override public void onBatch(PpgBatch batch) { out.onBatch(batch); }
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
            try { client.unsetEventListener(ServiceProtocol.TRACKER_PPG_ON_DEMAND); } catch (Throwable t) {
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
            if (types == null || !types.contains(ServiceProtocol.TRACKER_PPG_ON_DEMAND)) {
                out.onUnsupported();
                return;
            }
            client.createTracker(ServiceProtocol.TRACKER_PPG_ON_DEMAND);
            // Before code 2 and on the request-handler queue ahead of it: the service applies the
            // LED channels only when it (re)registers the tracker's sensor, so a setPpgType sent
            // after registration would not change anything until the tracker restarts. This is the
            // order the on-watch probe validated (codes 1, 8, 2).
            client.setPpgType(ServiceProtocol.TRACKER_PPG_ON_DEMAND, channels);
            // No lock is held here: PERMISSION_ERROR may call back on this thread.
            client.setEventListener(ServiceProtocol.TRACKER_PPG_ON_DEMAND, binder);
            if (!running) {
                client.unsetEventListener(ServiceProtocol.TRACKER_PPG_ON_DEMAND);
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
