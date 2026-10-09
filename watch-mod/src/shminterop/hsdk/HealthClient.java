package shminterop.hsdk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * Connection to the on-watch Health Tracking Service (docs/health-tracking-protocol.md, 2 and 5).
 * Raw {@link IBinder#transact} only. Callbacks of {@link Listener} run on the main thread and
 * must return quickly.
 */
public final class HealthClient {
    private static final String TAG = "HealthClient";
    static final long CONNECT_TIMEOUT_MS = 20000L;

    public interface Listener {
        /** The service binder is available; called again after the service restarts. */
        void onConnected(HealthClient client);
        /** bindService returned false, null binding, binding died, or the 20 s timeout elapsed. */
        void onConnectionFailed(String reason);
        /** The service process died; the system rebinds and {@link #onConnected} follows. */
        void onServiceLost();
        /** {@link #disconnect()} completed. */
        void onConnectionEnded();
    }

    private interface Request { void write(WireWriter w); }
    private interface Reply<T> { T read(WireReader r); }

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private ServiceConnection connection; // non-null while bound or binding
    private IBinder service;
    private IBinder.DeathRecipient death;

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            ServiceConnection c;
            synchronized (lock) { c = connection; }
            fail(c, "timeout waiting for the service");
        }
    };

    public HealthClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public String packageName() {
        return context.getPackageName();
    }

    /** Starts binding; the outcome is reported on the listener. No-op if already connecting. */
    public void connect() {
        final Conn conn;
        synchronized (lock) {
            if (connection != null) return;
            conn = new Conn();
            connection = conn;
        }
        boolean ok = false;
        String why = "bindService returned false";
        try {
            Intent i = new Intent(ServiceProtocol.SERVICE_ACTION).setPackage(ServiceProtocol.SERVICE_PACKAGE);
            ok = context.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            why = "bindService refused: " + e;
        }
        if (!ok) {
            fail(conn, why);
            return;
        }
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS);
    }

    /** Unbinds and reports onConnectionEnded synchronously. Callers send code 3 first. */
    public void disconnect() {
        ServiceConnection c;
        synchronized (lock) {
            c = connection;
            connection = null;
            clearServiceLocked();
        }
        main.removeCallbacks(timeout);
        if (c == null) return;
        try { context.unbindService(c); } catch (RuntimeException ignored) {}
        listener.onConnectionEnded();
    }

    public boolean isConnected() {
        synchronized (lock) { return service != null; }
    }

    // -------------------------------------------------------------- transactions

    /** Code 5. */
    public List<String> getSupportedTrackerTypes() throws RemoteException {
        return call(ServiceProtocol.TX_SUPPORTED_TYPES,
                new Request() {
                    @Override public void write(WireWriter w) { ServiceProtocol.writeSupportedTypesRequest(w); }
                },
                new Reply<List<String>>() {
                    @Override public List<String> read(WireReader r) { return ServiceProtocol.readStringList(r); }
                });
    }

    /** Code 1. */
    public void createTracker(final String trackerType) throws RemoteException {
        final String app = packageName();
        call(ServiceProtocol.TX_CREATE, new Request() {
            @Override public void write(WireWriter w) { ServiceProtocol.writeCreateRequest(w, app, trackerType); }
        }, null);
    }

    /**
     * Code 2. A PERMISSION_ERROR may be delivered to the listener binder on this very thread before
     * this returns, so callers must not hold a non-reentrant lock the error handler needs.
     */
    public void setEventListener(final String trackerType, final IBinder listenerBinder) throws RemoteException {
        final String app = packageName();
        call(ServiceProtocol.TX_SET_LISTENER, new Request() {
            @Override public void write(WireWriter w) {
                ServiceProtocol.writeSetListenerRequest(w, app, trackerType, listenerBinder);
            }
        }, null);
    }

    /** Code 3. */
    public void unsetEventListener(final String trackerType) throws RemoteException {
        final String app = packageName();
        call(ServiceProtocol.TX_UNSET_LISTENER, new Request() {
            @Override public void write(WireWriter w) { ServiceProtocol.writeUnsetListenerRequest(w, app, trackerType); }
        }, null);
    }

    private <T> T call(int code, Request req, Reply<T> rep) throws RemoteException {
        IBinder s;
        synchronized (lock) { s = service; }
        if (s == null) throw new RemoteException("not connected");
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            req.write(new ParcelWire(data));
            s.transact(code, data, reply, 0);
            reply.readException();
            return rep == null ? null : rep.read(new ParcelWire(reply));
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // -------------------------------------------------------------- state

    private void clearServiceLocked() {
        if (service != null && death != null) {
            try { service.unlinkToDeath(death, 0); } catch (RuntimeException ignored) {}
        }
        service = null;
        death = null;
    }

    private void fail(ServiceConnection c, String reason) {
        synchronized (lock) {
            if (c == null || connection != c) return;
            connection = null;
            clearServiceLocked();
        }
        main.removeCallbacks(timeout);
        try { context.unbindService(c); } catch (RuntimeException ignored) {}
        Log.w(TAG, "connection failed: " + reason);
        listener.onConnectionFailed(reason);
    }

    private void lost(IBinder b) {
        synchronized (lock) {
            if (service != b || b == null) return;
            clearServiceLocked();
        }
        listener.onServiceLost();
    }

    private final class Conn implements ServiceConnection {
        @Override public void onServiceConnected(ComponentName name, final IBinder binder) {
            synchronized (lock) {
                if (connection != this) return;
                clearServiceLocked();
                service = binder;
                death = new IBinder.DeathRecipient() {
                    @Override public void binderDied() { lost(binder); }
                };
                try {
                    binder.linkToDeath(death, 0);
                } catch (RemoteException e) {
                    // already dead: onServiceDisconnected follows
                    death = null;
                }
            }
            main.removeCallbacks(timeout);
            listener.onConnected(HealthClient.this);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            IBinder b;
            synchronized (lock) { b = service; }
            lost(b);
        }

        @Override public void onNullBinding(ComponentName name) {
            fail(this, "null binding (service not ready; retry later)");
        }

        @Override public void onBindingDied(ComponentName name) {
            fail(this, "binding died");
        }
    }
}
