package shminterop.shmbridge.watch;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.util.Log;

/**
 * Bound by the Companion Device Manager while the associated phone is connected (see
 * {@link CompanionLink}). The binding is what keeps the process alive; the link itself is
 * {@link RfcommLink}, which reconnects by itself.
 */
public class LinkPresenceService extends CompanionDeviceService {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(BridgeHub.TAG, "companion: bound");
        RfcommLink.start(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceAppeared(AssociationInfo info) {
        Log.i(BridgeHub.TAG, "companion: phone present (" + info.getId() + ")");
        RfcommLink.start(this);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onDeviceDisappeared(AssociationInfo info) {
        Log.i(BridgeHub.TAG, "companion: phone gone (" + info.getId() + ")");
    }

    @Override
    public void onDestroy() {
        Log.i(BridgeHub.TAG, "companion: unbound");
        super.onDestroy();
    }
}
