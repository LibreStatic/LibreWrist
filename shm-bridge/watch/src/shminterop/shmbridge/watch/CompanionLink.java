package shminterop.shmbridge.watch;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.Intent;
import android.net.MacAddress;
import android.os.Build;
import android.util.Log;

import java.util.List;

/**
 * Keeps the link alive without a foreground service. When the phone is associated with this app
 * in the Companion Device Manager (set up over adb by the installer), CDM
 * binds {@link LinkPresenceService} while the phone is connected over Bluetooth, which keeps the
 * process out of the cached/frozen states. Without an association the bridge falls back to
 * {@link BridgeService} (foreground service with its ongoing notification).
 */
final class CompanionLink {

    private CompanionLink() {}

    /**
     * Asks CDM to report the presence of every associated device (idempotent; CDM persists it).
     * @return true when at least one association is observed, i.e. no foreground service is needed.
     */
    @SuppressWarnings("deprecation")
    static boolean observe(Context c) {
        // onDeviceAppeared on a Bluetooth connection (not only BLE scans) needs API 33.
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            CompanionDeviceManager cdm = c.getSystemService(CompanionDeviceManager.class);
            if (cdm == null) return false;
            List<AssociationInfo> all = cdm.getMyAssociations();
            boolean observed = false;
            for (AssociationInfo a : all) {
                MacAddress mac = a.getDeviceMacAddress();
                if (mac == null) continue;
                try {
                    cdm.startObservingDevicePresence(mac.toString().toUpperCase(java.util.Locale.ROOT));
                    observed = true;
                } catch (Exception e) {
                    Log.w(BridgeHub.TAG, "companion: observe " + a.getId() + ": " + e);
                }
            }
            RfcommLink.companion = observed;
            return observed;
        } catch (Exception e) {
            Log.w(BridgeHub.TAG, "companion: " + e);
            return false;
        }
    }

    /**
     * Starts the link the best available way: companion presence when associated (no notification),
     * otherwise the foreground {@link BridgeService}. A refusal is logged, never thrown.
     */
    static void startLink(Context c, String why) {
        if (observe(c)) {
            RfcommLink.start(c);
            // A service left over from foreground mode would keep its notification.
            c.stopService(new Intent(c, BridgeService.class));
            Log.i(BridgeHub.TAG, "link: companion mode (" + why + ")");
            return;
        }
        try {
            c.startForegroundService(new Intent(c, BridgeService.class));
            Log.i(BridgeHub.TAG, "link: BridgeService requested (" + why + ")");
        } catch (Exception e) {
            Log.w(BridgeHub.TAG, "link: BridgeService not started (" + why + "): " + e.getClass().getSimpleName());
        }
    }
}
