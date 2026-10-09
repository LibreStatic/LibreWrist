package shminterop.shmbridge.phone;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;

/**
 * SHM relay rendezvous point over Bluetooth.
 *
 * <p>Accepts two clients: the phone's SHM (which connects to 127.0.0.1:8774,
 * as it always does) and the watch's BtRelayClient (RFCOMM, relay UUID). When
 * both are present, it joins them with a binary pipe. Replaces the TCP dial-out
 * of the classic startRelay, which depended on incoming WiFi reachability to the
 * watch (blocked by Wear OS for app sockets).</p>
 */
public class BtRelayServer extends Service {

    public static final UUID RELAY_UUID =
            UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3b");

    private volatile boolean running;
    private volatile BluetoothSocket watchSide;   // watch side (RFCOMM)
    private volatile Socket shmSide;              // SHM side (TCP 8774)
    private final Object lock = new Object();

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        synchronized (this) {
            if (running) return START_STICKY;
            running = true;
        }
        new Thread(new Runnable() {
            @Override public void run() { btAcceptLoop(); }
        }, "relay-bt-accept").start();
        new Thread(new Runnable() {
            @Override public void run() { tcpAcceptLoop(); }
        }, "relay-tcp-accept").start();
        new Thread(new Runnable() {
            @Override public void run() { matchLoop(); }
        }, "relay-match").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeWatch();
        closeShm();
        super.onDestroy();
    }

    private void startAsForeground() {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        android.app.NotificationChannel ch = new android.app.NotificationChannel(
                "bridge-bt", "SHM Bridge BT",
                android.app.NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        android.app.Notification n = new android.app.Notification.Builder(this, "bridge-bt")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SHM Bridge relay (BT)")
                .setContentText("watch ↔ SHM por RFCOMM")
                .build();
        try {
            startForeground(1003, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception fallback) {
            try { startForeground(1003, n); } catch (Exception ignored) {}
        }
    }

    /** Watch side: accepts RFCOMM; keeps the latest socket. */
    private void btAcceptLoop() {
        while (running) {
            try {
                BluetoothManager bm =
                        (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    Thread.sleep(3000);
                    continue;
                }
                BluetoothServerSocket sss = adapter.listenUsingInsecureRfcommWithServiceRecord(
                        "SHMBridgeRelay", RELAY_UUID);
                android.util.Log.i("SHMBridgePhone", "relay BT: escuchando UUID relay");
                BluetoothSocket s = sss.accept();   // blocking
                try { sss.close(); } catch (Exception ignored) {}
                android.util.Log.i("SHMBridgePhone",
                        "relay BT: watch connected (" + s.getRemoteDevice().getName() + ")");
                closeWatch();
                synchronized (lock) { watchSide = s; lock.notifyAll(); }
            } catch (Throwable t) {
                android.util.Log.i("SHMBridgePhone", "relay BT accept: " + t);
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }
    }

    /** SHM side: accepts on 8774; keeps the latest socket. */
    private void tcpAcceptLoop() {
        ServerSocket local = null;
        try {
            local = new ServerSocket(8774);
            android.util.Log.i("SHMBridgePhone", "relay TCP: 8774 escuchando");
        } catch (Exception e) {
            android.util.Log.i("SHMBridgePhone", "relay TCP 8774 ocupado: " + e);
            return;
        }
        while (running) {
            try {
                Socket s = local.accept();
                s.setKeepAlive(true);
                android.util.Log.i("SHMBridgePhone", "relay TCP: SHM connected");
                closeShm();
                synchronized (lock) { shmSide = s; lock.notifyAll(); }
            } catch (Throwable t) {
                android.util.Log.i("SHMBridgePhone", "relay TCP accept: " + t);
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }
    }

    /** Pairs watch+SHM when both exist; repeats when the session closes. */
    private void matchLoop() {
        while (running) {
            BluetoothSocket w;
            Socket s;
            synchronized (lock) {
                w = watchSide;
                s = shmSide;
                if (w == null || s == null) {
                    try { lock.wait(2000); } catch (InterruptedException e) { return; }
                    continue;
                }
                watchSide = null;
                shmSide = null;
            }
            android.util.Log.i("SHMBridgePhone", "relay: BT<->SHM session started");
            final BluetoothSocket wSock = w;
            final Socket sSock = s;
            Runnable killBoth = new Runnable() {
                @Override public void run() {
                    try { wSock.close(); } catch (Exception ignored) {}
                    try { sSock.close(); } catch (Exception ignored) {}
                }
            };
            try {
                Thread a = new Thread(new Pump(w.getInputStream(), s.getOutputStream(), "watch->shm", killBoth));
                Thread b = new Thread(new Pump(s.getInputStream(), w.getOutputStream(), "shm->watch", killBoth));
                a.start(); b.start();
                a.join(15000); b.join(15000);
            } catch (Throwable t) {
                android.util.Log.i("SHMBridgePhone", "relay session: " + t);
            } finally {
                killBoth.run();
                android.util.Log.i("SHMBridgePhone", "relay: session closed; waiting for both sides");
            }
        }
    }

    private void closeWatch() {
        BluetoothSocket w = watchSide;
        watchSide = null;
        if (w != null) try { w.close(); } catch (Exception ignored) {}
    }

    private void closeShm() {
        Socket s = shmSide;
        shmSide = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    /** When one pump dies, BOTH ends are closed (wakes the sibling
     *  blocked on a write to a dead RFCOMM). */
    static class Pump implements Runnable {
        private final InputStream in;
        private final OutputStream out;
        private final String tag;
        private final Runnable onExit;
        Pump(InputStream in, OutputStream out, String tag, Runnable onExit) {
            this.in = in; this.out = out; this.tag = tag; this.onExit = onExit;
        }
        @Override public void run() {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                android.util.Log.i("SHMBridgePhone", "relay pump " + tag + " fin: " + e);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { out.close(); } catch (Exception ignored) {}
                if (onExit != null) onExit.run();
            }
        }
    }
}
