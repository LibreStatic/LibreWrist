package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Random;

/**
 * Plain-text TCP server (one line per command). LAN only + pairing token.
 * The protocol lives in {@link ProtoHandler}; this service only handles the TCP accept.
 */
public class BridgeService extends Service {

    public static final int PORT = 8771;
    private static final String PREFS = "bridge";
    private static final String KEY_TOKEN = "token";
    static final String CH = "bridge";

    private Thread acceptThread;
    private volatile ServerSocket server;
    private volatile boolean running;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        // BT RFCOMM client: reconnects to the phone by itself (channel parallel to TCP).
        BtClient.start(this);
        BtRelayClient.start(this);
        synchronized (this) {
            if (!running) {
                running = true;
                acceptThread = new Thread(new Runnable() {
                    @Override public void run() { acceptLoop(); }
                }, "bridge-accept");
                acceptThread.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startAsForeground() {
        // Debugging convenience: the token is also left in logcat (remove in production).
        android.util.Log.i("SHMBridge", "bridge token=" + token(this) + " ip=" + localIp());
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "SHM Bridge",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SHM Bridge")
                .setContentText("Escuchando en " + PORT)
                .build();
        // connectedDevice and not dataSync: since API 35 dataSync has a 6 h per day cap; once
        // exhausted, startForeground throws ForegroundServiceStartNotAllowedException, the service
        // crashes and the RFCOMM link stays down until the next day.
        try {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (SecurityException e) {
            android.util.Log.w("SHMBridge", "FGS connectedDevice rechazado, uso dataSync: " + e.getMessage());
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }

    private void acceptLoop() {
        try {
            server = new ServerSocket(PORT);
            while (running) {
                final Socket s = server.accept();
                new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "bridge-client").start();
            }
        } catch (Exception e) {
            if (running) { /* port closed when the service is destroyed */ }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            ProtoHandler.run(this, in, out);
            s.close();
        } catch (Exception ignored) {
        }
    }

    /** Launches an SHM activity; shared between the TCP and the BT transport. */
    static String launch(Context ctx, String cls) {
        // keep the screen on during the measurement (without a screen, SHM's window
        // goes to Doze and the result capture sees nothing)
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            @SuppressWarnings("deprecation")
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "shmbridge:measure");
            wl.acquire(15000);
        } catch (Throwable ignored) {}
        try {
            if (hasBalExemption(ctx)) {
                Intent i = new Intent();
                i.setClassName(MainActivity.SHM_PKG, cls);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "OK " + cls.substring(cls.lastIndexOf('.') + 1);
            }
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
        // Without the BAL exemption: One UI aborts the background launch. Fallback:
        // heads-up notification with PendingIntent — tapping it on the watch launches it
        // (touch is always allowed; besides, ECG/BP requires physical interaction anyway).
        try {
            String label = cls.substring(cls.lastIndexOf('.') + 1);
            Intent i = new Intent();
            i.setClassName(MainActivity.SHM_PKG, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, cls.hashCode(), i,
                    android.app.PendingIntent.FLAG_IMMUTABLE);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = new Notification.Builder(ctx, CH)
                    .setSmallIcon(android.R.drawable.ic_menu_send)
                    .setContentTitle("SHM Bridge")
                    .setContentText("Toca para iniciar " + label)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();
            nm.notify(("shm-launch-" + cls).hashCode() & 0x7fffffff, n);
            return "OK-NOTIF " + label;
        } catch (Exception e) {
            return "ERR " + e.getMessage();
        }
    }

    private static boolean hasBalExemption(Context c) {
        try {
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(), c.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String token(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = p.getString(KEY_TOKEN, null);
        if (t == null) {
            Random r = new Random();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
            t = sb.toString();
            p.edit().putString(KEY_TOKEN, t).apply();
        }
        return t;
    }

    public static String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4 && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }
}
