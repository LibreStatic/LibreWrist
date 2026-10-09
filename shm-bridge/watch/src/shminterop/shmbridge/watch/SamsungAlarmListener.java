package shminterop.shmbridge.watch;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Sees the ringing-alarm notification of Samsung's Alarm app (package
 * {@link SamsungAlarm#PKG}, {@code category=alarm}) to know when it starts and when the
 * user turns it off. It only looks at that package; it reads no texts. Access via adb:
 * {@code cmd notification allow_listener shminterop.shmbridge.watch/shminterop.shmbridge.watch.SamsungAlarmListener}.
 */
public class SamsungAlarmListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification sbn : all) onNotificationPosted(sbn);
        } catch (Exception ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // when == 0: service start/stop notification, not a ringing alarm
        if (isFiring(sbn) && sbn.getNotification().when != 0) SamsungAlarm.onFiring(getApplicationContext(), sbn.getNotification().when);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isFiring(sbn)) SamsungAlarm.onStopped(getApplicationContext());
    }

    private static boolean isFiring(StatusBarNotification sbn) {
        return SamsungAlarm.PKG.equals(sbn.getPackageName())
                && Notification.CATEGORY_ALARM.equals(sbn.getNotification().category);
    }
}
