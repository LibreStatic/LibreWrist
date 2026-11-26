package shminterop.hsdk;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PPG tracker of the Health Tracking Service: connect, capability check, create, select the LED
 * channels, register the listener, deliver batches and errors; re-arms after a service restart.
 *
 * {@link Listener} callbacks may run on binder or main threads and must return quickly.
 */
public final class PpgTracker {
    private static final String TAG = "PpgTracker";
    /** How long {@link #stop} waits for an arm already past its running check to finish. */
    static final long ARM_WAIT_MS = 2000L;

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
    private final String trackerType;
    private final List<String> channels;
    private final ListenerBinder binder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;
    /** True between a successful code 2 of this tracker and its code 3. */
    private volatile boolean registered;

    /** Green-only PPG_ON_DEMAND tracker (BP). */
    public PpgTracker(Context context, Listener listener) {
        this(context, Collections.singletonList(ServiceProtocol.PPG_TYPE_GREEN), listener);
    }

    /** PPG_ON_DEMAND tracker for the given LED channels (ServiceProtocol.PPG_TYPE_*). */
    public PpgTracker(Context context, List<String> channels, Listener listener) {
        this(context, ServiceProtocol.TRACKER_PPG_ON_DEMAND, channels, listener);
    }

    /**
     * Tracker of the given PPG type (ServiceProtocol.TRACKER_PPG_ON_DEMAND or
     * TRACKER_PPG_CONTINUOUS) for the given LED channels, e.g. RED and IR for SpO2.
     */
    public PpgTracker(Context context, String trackerType, List<String> channels, Listener listener) {
        this.trackerType = trackerType;
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

    /**
     * Code 3 (only for a listener this tracker registered), then unbind.
     *
     * The service keys listeners by appId and tracker type, so another tracker of this app may
     * register the same key right after this returns (BP taking the sensor from the apnea
     * recording in its PPG_ON_DEMAND fallback). Waiting for an arm already in flight guarantees
     * that no registration of this tracker commits after stop() returns, and that its cleanup
     * cannot detach the other tracker's listener. The worker's callbacks take no lock of the
     * callers, so waiting here cannot deadlock.
     */
    public void stop() {
        running = false;
        worker.shutdown(); // no new arm; an arm in flight sees running == false and undoes itself
        try {
            if (!worker.awaitTermination(ARM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "arm still running after " + ARM_WAIT_MS + " ms; stopping anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        unsetIfRegistered();
        client.disconnect();
        worker.shutdownNow();
    }

    private void unsetIfRegistered() {
        if (!registered || !client.isConnected()) return;
        registered = false;
        try { client.unsetEventListener(trackerType); } catch (Throwable t) {
            Log.w(TAG, "unset failed: " + t);
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
            if (types == null || !types.contains(trackerType)) {
                out.onUnsupported();
                return;
            }
            client.createTracker(trackerType);
            // Before code 2 and on the request-handler queue ahead of it: the service applies the
            // LED channels only when it (re)registers the tracker's sensor, so a setPpgType sent
            // after registration would not change anything until the tracker restarts. This is the
            // order the on-watch probe validated (codes 1, 8, 2).
            client.setPpgType(trackerType, channels);
            // No lock is held here: PERMISSION_ERROR may call back on this thread.
            client.setEventListener(trackerType, binder);
            registered = true;
            if (!running) {
                unsetIfRegistered();
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
