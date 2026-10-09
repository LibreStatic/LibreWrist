package shminterop.shmbridge.phone;

import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Client side of the SHMBRIDGE protocol over the RFCOMM session accepted by
 * BtServer. A single reader thread queues the lines arriving from the watch; the UI
 * (MainActivity) sends a command and waits for the next response from the queue.
 * Session exposed statically so the UI can use it without depending on TCP.
 */
public final class BtProtoClient {

    /** Active BT session (null if none). */
    public static volatile Session active;

    public static final class Session {
        public final BufferedReader in;
        public final PrintWriter out;
        public final BluetoothSocket sock;
        public final String deviceName;
        private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private volatile boolean alive = true;

        Session(BufferedReader in, PrintWriter out, BluetoothSocket sock, String deviceName) {
            this.in = in; this.out = out; this.sock = sock; this.deviceName = deviceName;
        }

        /** Sends a command and returns the response line (null if it died / timeout). */
        public String command(String cmd, long timeoutMs) {
            if (!alive) return null;
            try {
                queue.clear();
                out.println(cmd);
                String line = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                return line;
            } catch (Exception e) {
                return null;
            }
        }

        public boolean isAlive() { return alive; }
    }

    private BtProtoClient() {}

    /** Client handshake over the accepted socket; leaves the session in {@link #active}. */
    static void runCommandLoop(Context ctx, BufferedReader in, PrintWriter out,
                               BluetoothSocket sock, String deviceName) throws Exception {
        String banner = in.readLine();
        if (banner == null || !banner.startsWith("SHMBRIDGE")) {
            throw new IllegalStateException("banner inesperado: " + banner);
        }
        String tok = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getString("token", "");
        out.println("AUTH " + tok);
        String resp = in.readLine();
        if (!"OK".equals(resp)) {
            throw new IllegalStateException("auth failed: " + resp);
        }
        android.util.Log.i("SHMBridgePhone", "BT: session authenticated with " + deviceName);
        Session s = new Session(in, out, sock, deviceName);
        active = s;
        try {
            String line;
            while (s.alive && (line = in.readLine()) != null) {
                s.queue.offer(line);
            }
        } catch (Exception ignored) {
        } finally {
            s.alive = false;
            active = null;
            android.util.Log.i("SHMBridgePhone", "BT: session closed with " + deviceName);
        }
    }
}
