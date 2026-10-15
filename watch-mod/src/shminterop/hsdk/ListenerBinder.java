package shminterop.hsdk;

import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Client-side callback object passed to the service (descriptor IServiceEventListener). One
 * instance per tracker registration: the sink given at construction decides whether CB_DATA
 * carries ECG or PPG batches, since the wire callback codes are shared.
 */
final class ListenerBinder extends Binder {
    private static final String TAG = "HealthClient";
    private static final int FIRST_CALL = 1;
    private static final int LAST_CALL = 0x00ffffff;

    private final ServiceProtocol.EventSink ecgSink;
    private final ServiceProtocol.PpgSink ppgSink;

    ListenerBinder(ServiceProtocol.EventSink sink) {
        this(sink, null);
    }

    ListenerBinder(ServiceProtocol.PpgSink sink) {
        this(null, sink);
    }

    private ListenerBinder(ServiceProtocol.EventSink ecgSink, ServiceProtocol.PpgSink ppgSink) {
        this.ecgSink = ecgSink;
        this.ppgSink = ppgSink;
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
                if (ppgSink != null) {
                    ServiceProtocol.handlePpgEvent(code, new ParcelWire(data),
                            Build.VERSION.SDK_INT >= 33, ppgSink);
                } else {
                    ServiceProtocol.handleEvent(code, new ParcelWire(data),
                            Build.VERSION.SDK_INT >= 33, ecgSink);
                }
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
