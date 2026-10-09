package shminterop.shmbridge.watch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.UUID;

/**
 * RFCOMM (SPP) client of the bridge. Runs on a daemon thread while the
 * BridgeService is alive; reconnects with backoff if the link drops.
 * The phone runs BtServer (listening on the same UUID) and the watch connects
 * as a client — this avoids depending on the watch accepting
 * incoming connections (which on Wear OS usually requires user consent).
 */
public final class BtClient {
    /** MAC prefix (OUI, e.g. "AA:BB:CC") of the phone to try first; empty = no priority. */
    private static final String PREFERRED_OUI = "";

    public static final UUID SDP_UUID =
            UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3a");

    private static volatile Thread worker;
    private static volatile boolean running;
    /** Last error / state, to show in the watch UI. */
    public static volatile String status = "detenido";

    private BtClient() {}

    public static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(app); }
        }, "bridge-bt-client");
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
            try {
                BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    status = "BT apagado";
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                    continue;
                }
                // Already paired device (existing audio link). No scan or pairing:
                // we only try the RFCOMM channel with each bonded device that has SPP.
                java.util.Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded == null || bonded.isEmpty()) {
                    status = "sin dispositivos emparejados";
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                    continue;
                }
                boolean connected = false;
                // Prioritize the phone by MAC prefix (OUI), if configured in PREFERRED_OUI:
                // avoids spending the timeout on other paired devices before the right one.
                java.util.List<BluetoothDevice> sorted = new java.util.ArrayList<>(bonded);
                java.util.Collections.sort(sorted, new java.util.Comparator<BluetoothDevice>() {
                    @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                        String aa = a.getAddress() == null ? "" : a.getAddress().toUpperCase();
                        String bb = b.getAddress() == null ? "" : b.getAddress().toUpperCase();
                        boolean aPref = !PREFERRED_OUI.isEmpty() && aa.startsWith(PREFERRED_OUI);
                        boolean bPref = !PREFERRED_OUI.isEmpty() && bb.startsWith(PREFERRED_OUI);
                        if (aPref != bPref) return aPref ? -1 : 1;
                        return 0;
                    }
                });
                for (BluetoothDevice dev : sorted) {
                    if (!running) return;
                    try {
                        status = "conectando a " + dev.getName();
                        android.util.Log.i("SHMBridge", "BT: trying " + dev.getName()
                                + " (" + dev.getAddress() + ")");
                        // createRfcommSocketToServiceRecord: requires the remote to have the
                        // SDP record registered (BtServer on the phone). If it does not, it fails
                        // fast (IOException) and we try the next one.
                        // Insecure: does not require BR/EDR encryption (avoids re-pairing with the
                        // existing LE audio bond). The app has its own token AUTH.
                        BluetoothSocket sock = dev.createInsecureRfcommSocketToServiceRecord(SDP_UUID);
                        try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
                        sock.connect();
                        status = "conectado a " + dev.getName();
                        android.util.Log.i("SHMBridge", "BT: connected to " + dev.getName());
                        connected = true;
                        backoff = 2000; // reset after a successful connection
                        try {
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(sock.getInputStream(), "UTF-8"));
                            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
                            ProtoHandler.run(ctx, in, out);
                        } finally {
                            try { sock.close(); } catch (Exception ignored) {}
                            status = "desconectado de " + dev.getName();
                            android.util.Log.i("SHMBridge", "BT: session closed");
                        }
                        break;
                    } catch (Exception e) {
                        android.util.Log.i("SHMBridge", "BT: falla " + dev.getName() + ": " + e);
                    }
                }
                if (!connected) {
                    status = "sin servidor BT (reintentando)";
                    sleep(backoff);
                    backoff = Math.min(backoff * 2, 30000);
                }
            } catch (Throwable t) {
                status = "error: " + t.getClass().getSimpleName();
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
