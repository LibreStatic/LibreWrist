package shminterop.shmbridge.watch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.util.UUID;

/**
 * Direct RFCOMM session to the phone, driven by {@link FrameMux}: the {@code /__bridge__/}
 * channel (notifications, alarms, calls) is served in-process by {@link BridgeHub}.
 *
 * <p>Wear OS only lets incoming connections through to system processes (adbd), not to app
 * sockets, so the watch connects outward over RFCOMM (allowed) to the server the phone
 * bridge ({@code BridgeLink.rfcomm()}) keeps open on {@link #RFCOMM_UUID}. The link reconnects
 * with backoff whenever it drops.</p>
 */
public final class RfcommLink {

    public static final UUID RFCOMM_UUID =
            UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3b");

    private static volatile Thread worker;
    private static volatile boolean running;
    public static volatile String status = "detenido";

    private RfcommLink() {}

    public static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        BridgeHub.get(app); // notification channels ready before the first frame
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(app); }
        }, "bridge-rfcomm");
        worker.setDaemon(true);
        worker.start();
    }

    public static synchronized void stop() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
        status = "detenido";
    }

    private static void loop(Context ctx) {
        long backoff = 2000;
        while (running) {
            BluetoothAdapter adapter = null;
            try {
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                adapter = bm == null ? null : bm.getAdapter();
            } catch (Exception e) {
                android.util.Log.w("SHMBridge", "rfcomm: no BT manager: " + e);
            }
            if (adapter == null || !adapter.isEnabled()) {
                status = "BT apagado";
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
                continue;
            }
            java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                status = "sin emparejados";
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
                continue;
            }
            boolean served = false;
            for (BluetoothDevice dev : bonded) {
                if (!running) return;
                try {
                    status = "conectando " + dev.getName();
                    BluetoothSocket sock =
                            dev.createInsecureRfcommSocketToServiceRecord(RFCOMM_UUID);
                    try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
                    sock.connect();
                    status = "enlazado (" + dev.getName() + ")";
                    android.util.Log.i("SHMBridge", "rfcomm: ready with " + dev.getName());
                    backoff = 2000;
                    served = pipeSession(sock, ctx);
                } catch (Exception e) {
                    android.util.Log.i("SHMBridge", "rfcomm: " + dev.getName() + ": " + e);
                }
                if (served) break; // full session; the outer loop retries the link
            }
            if (!served) {
                status = "sin servidor (reintentando)";
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    /**
     * Serves the RFCOMM session with {@link FrameMux}: {@code /__bridge__/*} frames go to
     * {@link BridgeHub}. Blocks until the RFCOMM dies or desynchronizes.
     * @return true whenever there was a BT link (do not try other devices).
     */
    private static boolean pipeSession(final BluetoothSocket sock, final Context ctx) {
        try {
            final BridgeHub hub = BridgeHub.get(ctx);
            FrameMux.Listener events = new FrameMux.Listener() {
                @Override public void onSessionOpen(FrameMux mux) {
                    status = "enlazado, sesión abierta";
                    hub.onSessionOpen(mux);
                }
                @Override public void onBridgeFrame(FrameMux mux, String path, byte[] data) {
                    hub.onBridgeFrame(mux, path, data);
                }
                @Override public void onSessionClosed(FrameMux mux, String reason) {
                    hub.onSessionClosed(mux, reason);
                }
            };
            FrameMux mux = new FrameMux(sock.getInputStream(), sock.getOutputStream(), sock,
                    events, new FrameMux.Logger() {
                        @Override public void log(String msg) {
                            android.util.Log.i(BridgeHub.TAG, "mux: " + msg);
                        }
                    });
            android.util.Log.i("SHMBridge", "rfcomm: session with FrameMux active");
            mux.run();
            return true;
        } catch (Throwable t) {
            android.util.Log.i("SHMBridge", "rfcomm: session: " + t);
            return true; // there was a BT link: do not try other devices
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
            status = "sesión cerrada";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
