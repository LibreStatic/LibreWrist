package shminterop.shmbridge.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/** Bridge FGS: with a foreground service the process does not enter the
 *  cached app freezer (which froze the process in the background and aborted the
 *  relay sockets ~5 s after launching SHM on top). */
public class RelayKeepAliveService extends Service {

    private static final String CH = "shmbridge";

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "SHM Bridge",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SHM Bridge")
                .setContentText("Puente SHM <-> reloj activo")
                .build();
        startForeground(2, n);
        Log.i("SHMBridgePhone", "FGS keep-alive activo");
    }
}
