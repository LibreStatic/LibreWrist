package shminterop.shmbridge.phone;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Notification listener for the {@code /__bridge__/notif/*} channel. Enabling without a
 * screen (APK from an external source):
 * <pre>
 *   appops set --user 0 shminterop.shmbridge.phone ACCESS_RESTRICTED_SETTINGS allow
 *   cmd notification allow_listener shminterop.shmbridge.phone/shminterop.shmbridge.phone.BridgeNotificationListener 0
 * </pre>
 * On connect it starts CommsService (the FGS that holds the link).
 */
public class BridgeNotificationListener extends NotificationListenerService {

    static volatile BridgeNotificationListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        Log.i(CommsHub.TAG, "listener connected");
        try {
            startForegroundService(new Intent(this, CommsService.class));
        } catch (Exception e) {
            // without a background-start exemption: the hub still runs in this process
            Log.w(CommsHub.TAG, "listener: no pude arrancar CommsService: " + e.getClass().getSimpleName());
            CommsHub.get(this).start();
        }
        CommsHub.get(this).onListenerConnected();
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
        Log.i(CommsHub.TAG, "listener desconectado");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        CommsHub.get(this).onNotifPosted(sbn, rankingMap);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap, int reason) {
        CommsHub.get(this).onNotifRemoved(sbn, reason);
    }
}
