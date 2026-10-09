package shminterop.shmbridge.watch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

/**
 * RFCOMM session of SHM's relay, demultiplexed by {@link FrameMux}: SHM frames
 * travel untouched to and from 127.0.0.1:8773, those of the {@code /__bridge__/} channel are served
 * in-process ({@link BridgeHub}: notifications, alarms, calls) and those of the
 * {@code /__gw__/} channel by {@link GwHub} (phase 2: state for Galaxy Wearable / heartplugin).
 *
 * <p>Wear OS only lets incoming connections through to system processes (adbd),
 * not to app sockets: that is why the SHM mod's TCP relay (listening on
 * localhost:8773) is not reachable from WiFi. This client connects
 * outward over RFCOMM (allowed) to the phone's BtRelayServer (or a host gateway) and
 * forwards the frames to the local 8773, completing the chain SHM-phone ↔ BT ↔ SHM-watch.
 * The 8773 is optional: without SHM the session stays alive for the channels.</p>
 */
public final class BtRelayClient {

    public static final UUID RELAY_UUID =
            UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3b");

    /** Port of the SHM mod's relay on the watch (localhost). */
    static final int SHM_PORT = 8773;

    private static volatile Thread worker;
    private static volatile boolean running;
    public static volatile String status = "detenido";

    private BtRelayClient() {}

    public static synchronized void start(Context ctx) {
        if (running) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        BridgeHub.get(app); // notification channels ready before the first frame
        GwHub.get(app);     // GW store and battery receiver ready before the first frame
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(app); }
        }, "bridge-bt-relay");
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
                android.util.Log.w("SHMBridge", "relay: no BT manager: " + e);
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
            java.util.List<BluetoothDevice> sorted = new java.util.ArrayList<>(bonded);
            java.util.Collections.sort(sorted, new java.util.Comparator<BluetoothDevice>() {
                @Override public int compare(BluetoothDevice a, BluetoothDevice b) {
                    String aa = a.getAddress() == null ? "" : a.getAddress().toUpperCase();
                    String bb = b.getAddress() == null ? "" : b.getAddress().toUpperCase();
                    boolean aMoto = aa.startsWith("B8:34:46");
                    boolean bMoto = bb.startsWith("B8:34:46");
                    if (aMoto != bMoto) return aMoto ? -1 : 1;
                    return 0;
                }
            });
            for (BluetoothDevice dev : sorted) {
                if (!running) return;
                try {
                    status = "relay: conectando " + dev.getName();
                    BluetoothSocket sock =
                            dev.createInsecureRfcommSocketToServiceRecord(RELAY_UUID);
                    try { adapter.cancelDiscovery(); } catch (Exception ignored) {}
                    sock.connect();
                    status = "relay: BT listo (" + dev.getName() + ")";
                    android.util.Log.i("SHMBridge", "relay: RFCOMM ready with " + dev.getName());
                    backoff = 2000;
                    served = pipeSession(sock, ctx);
                } catch (Exception e) {
                    android.util.Log.i("SHMBridge", "relay: " + dev.getName() + ": " + e);
                }
                if (served) break; // full session; the outer loop retries the link
            }
            if (!served) {
                status = "relay: sin servidor (reintentando)";
                sleep(backoff);
                backoff = Math.min(backoff * 2, 30000);
            }
        }
    }

    /**
     * Serves the RFCOMM session with {@link FrameMux}: {@code /__bridge__/*} frames to
     * {@link BridgeHub}, {@code /__gw__/*} frames to {@link GwHub} and the rest byte by byte to
     * the SHM's 8773. The 8773 is optional (hooked up when it appears and dropped if it falls without
     * taking the RFCOMM down). Blocks until the RFCOMM dies or desynchronizes; then
     * both sides are closed.
     * @return true whenever there was a BT link (do not try other devices).
     */
    private static boolean pipeSession(final BluetoothSocket sock, final Context ctx) {
        try {
            final BridgeHub hub = BridgeHub.get(ctx);
            final GwHub gwHub = GwHub.get(ctx);
            FrameMux.LocalConnector shm = new FrameMux.LocalConnector() {
                @Override public FrameMux.Endpoint connect() throws java.io.IOException {
                    final Socket s = new Socket();
                    try {
                        // loopback: with no SHM listening, the connect fails instantly
                        s.connect(new InetSocketAddress("127.0.0.1", SHM_PORT), 3000);
                        s.setTcpNoDelay(true);
                    } catch (java.io.IOException e) {
                        try { s.close(); } catch (Exception ignored) {}
                        throw e;
                    }
                    return new FrameMux.Endpoint() {
                        @Override public java.io.InputStream in() throws java.io.IOException { return s.getInputStream(); }
                        @Override public java.io.OutputStream out() throws java.io.IOException { return s.getOutputStream(); }
                        @Override public void close() throws java.io.IOException { s.close(); }
                    };
                }
            };
            FrameMux.Listener events = new FrameMux.Listener() {
                @Override public void onSessionOpen(FrameMux mux) {
                    status = "relay: BT listo, esperando SHM";
                    hub.onSessionOpen(mux);
                    gwHub.onSessionOpen(mux);
                }
                @Override public void onBridgeFrame(FrameMux mux, String path, byte[] data) {
                    hub.onBridgeFrame(mux, path, data);
                }
                @Override public void onGwFrame(FrameMux mux, String path, byte[] data) {
                    gwHub.onGwFrame(mux, path, data);
                }
                @Override public void onLocalState(FrameMux mux, boolean attached) {
                    status = attached ? "relay: enlazado a SHM watch" : "relay: BT listo, sin SHM (reintentando)";
                }
                @Override public void onSessionClosed(FrameMux mux, String reason) {
                    hub.onSessionClosed(mux, reason);
                    gwHub.onSessionClosed(mux, reason);
                }
            };
            FrameMux mux = new FrameMux(sock.getInputStream(), sock.getOutputStream(), sock,
                    shm, events, new FrameMux.Logger() {
                        @Override public void log(String msg) {
                            android.util.Log.i(BridgeHub.TAG, "mux: " + msg);
                        }
                    });
            android.util.Log.i("SHMBridge", "relay: RFCOMM session with FrameMux active");
            mux.run();
            return true;
        } catch (Throwable t) {
            android.util.Log.i("SHMBridge", "relay: session: " + t);
            return true; // there was a BT link: do not try other devices
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
            status = "relay: sesión cerrada";
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
