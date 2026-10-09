package shminterop.hsdk;

import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/** Client-side callback object passed to the service (descriptor IServiceEventListener). */
final class ListenerBinder extends Binder {
    private static final String TAG = "HealthClient";
    private static final int FIRST_CALL = 1;
    private static final int LAST_CALL = 0x00ffffff;

    private final ServiceProtocol.EventSink sink;

    ListenerBinder(ServiceProtocol.EventSink sink) {
        this.sink = sink;
        attachInterface(null, ServiceProtocol.LISTENER_DESCRIPTOR);
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(ServiceProtocol.LISTENER_DESCRIPTOR);
            return true;
        }
        if (code >= FIRST_CALL && code <= LAST_CALL) {
            data.enforceInterface(ServiceProtocol.LISTENER_DESCRIPTOR);
            try {
                ServiceProtocol.handleEvent(code, new ParcelWire(data),
                        Build.VERSION.SDK_INT >= 33, sink);
            } catch (Throwable t) {
                // An exception here would make the service drop our registration.
                Log.w(TAG, "listener callback failed: " + t);
            }
            if (reply != null) reply.writeNoException();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
