package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Foreground service that hosts the RFCOMM link to the phone ({@link RfcommLink}).
 */
public class BridgeService extends Service {

    static final String CH = "bridge";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        // Reconnects to the phone by itself.
        RfcommLink.start(this);
        return START_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "SHM Bridge",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SHM Bridge")
                .setContentText("Enlace con el teléfono")
                .build();
        // connectedDevice and not dataSync: since API 35 dataSync has a 6 h per day cap; once
        // exhausted, startForeground throws ForegroundServiceStartNotAllowedException, the service
        // crashes and the RFCOMM link stays down until the next day.
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }
}
