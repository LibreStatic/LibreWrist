package shminterop.shmbridge.phone;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.util.UUID;

/**
 * RFCOMM (SPP) server of the bridge on the phone. Accepts incoming connections
 * from the watch (BtClient) on the shared UUID and runs the same command
 * protocol as the TCP channel (see BtProtoClient / MainActivity).
 */
public class BtServer extends Service {

    public static final UUID SDP_UUID =
            UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3a");
    public static final String SDP_NAME = "SHMBridge";

    private volatile BluetoothServerSocket serverSocket;
    private volatile boolean running;
    private Thread acceptThread;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        synchronized (this) {
            if (!running) {
                running = true;
                acceptThread = new Thread(new Runnable() {
                    @Override public void run() { acceptLoop(); }
                }, "bridge-bt-accept");
                acceptThread.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startAsForeground() {
        android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        android.app.NotificationChannel ch = new android.app.NotificationChannel("bridge-bt",
                "SHM Bridge BT", android.app.NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        android.app.Notification n = new android.app.Notification.Builder(this, "bridge-bt")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("SHM Bridge BT")
                .setContentText("Esperando reloj por Bluetooth")
                .build();
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private void acceptLoop() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            android.util.Log.i("SHMBridgePhone", "BT server: adaptador no disponible/apagado");
            return;
        }
        try {
            // Insecure: does not require BR/EDR encryption (avoids re-pairing). AUTH at app level.
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SDP_NAME, SDP_UUID);
        } catch (Exception e) {
            android.util.Log.i("SHMBridgePhone", "BT server: listen failed: " + e);
            return;
        }
        android.util.Log.i("SHMBridgePhone", "BT server: escuchando UUID " + SDP_UUID);
        while (running) {
            try {
                final BluetoothSocket sock = serverSocket.accept();
                android.util.Log.i("SHMBridgePhone", "BT server: connection from "
                        + sock.getRemoteDevice().getName());
                new Thread(new Runnable() {
                    @Override public void run() { handle(sock); }
                }, "bridge-bt-session").start();
            } catch (Exception e) {
                if (running) {
                    android.util.Log.i("SHMBridgePhone", "BT server: accept error: " + e);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    private void handle(BluetoothSocket sock) {
        try {
            java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(sock.getInputStream(), "UTF-8"));
            java.io.PrintWriter out = new java.io.PrintWriter(sock.getOutputStream(), true);
            String name = "reloj";
            try { name = String.valueOf(sock.getRemoteDevice().getName()); } catch (Exception ignored) {}
            BtProtoClient.runCommandLoop(this, in, out, sock, name);
        } catch (Exception e) {
            android.util.Log.i("SHMBridgePhone", "BT session err: " + e);
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }
}
