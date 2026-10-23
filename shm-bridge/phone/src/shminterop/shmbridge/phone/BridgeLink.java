package shminterop.shmbridge.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Link of the {@code /__bridge__/} communications channel (docs/bridge-comms-protocol.md).
 *
 * <p>Direct RFCOMM session: the phone is the RFCOMM server (service UUID {@link #RFCOMM_UUID});
 * the watch's {@code RfcommLink} connects by itself (it tries the paired devices) and its
 * {@code FrameMux} sends the {@code /__bridge__/*} frames here. Frames that do not belong to
 * the channel (bare heartbeats, any other path) are ignored.</p>
 *
 * <p>Frame: {@code [int len][short pathLen][path UTF-8][data]} big-endian. The own keepalive
 * is an empty {@code /__bridge__/ping} after 2 min without outgoing frames; bare heartbeats
 * (len=2, pathLen=0) are not part of the channel and are never sent. Reconnects with back-off;
 * if no frame arrives in 6 min of awake time (the watch answers every ping, even when the ping
 * woke it up) the connection is considered dead (watched by the writer: RFCOMM has no read
 * timeout). The period is long on purpose: every ping wakes the watch CPU, and a seconds-scale
 * keepalive kept it awake most of the night. A lost Bluetooth link closes the socket by itself;
 * the watchdog only catches a session that stopped answering.</p>
 */
final class BridgeLink {

    interface Listener {
        void onLinkUp();
        void onLinkDown();
        void onFrame(String path, byte[] data);
    }

    static final String TAG = "SHMBridgeComms";
    static final String PREFIX = "/__bridge__/";
    static final String PING = PREFIX + "ping";
    static final int MAX_FRAME = 16 * 1024 * 1024;
    static final int MAX_JSON = 64 * 1024;
    static final long PING_MS = 120_000;
    static final int READ_TIMEOUT_MS = 360_000;
    private static final byte[] STOP = new byte[0];
    /** Same UUID as the watch's {@code RfcommLink.RFCOMM_UUID}. */
    static final UUID RFCOMM_UUID = UUID.fromString("8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3b");

    /** One connection opened by the transport. */
    interface Conn extends Closeable {
        InputStream in() throws IOException;
        OutputStream out() throws IOException;
        String describe();
    }

    /** Opens (or waits for) the next connection; blocks. */
    interface Transport {
        Conn open() throws IOException;
        /** Unblocks an in-progress open() (stop). */
        void abort();
        String describe();
    }

    private final Listener listener;
    private final Transport transport;
    private volatile boolean running;
    private volatile Conn socket;
    private volatile long lastRx;       // SystemClock.uptimeMillis() of the last frame received
    private volatile long lastFrameAt;  // wall clock of the last received frame; 0 = never
    private volatile boolean up;      // socket connected (writable)
    private volatile boolean ready;   // and, in addition, the host has already sent a frame
    private Thread worker;
    private final LinkedBlockingQueue<byte[]> outq = new LinkedBlockingQueue<>(512);

    BridgeLink(Listener listener, Transport transport) {
        this.listener = listener;
        this.transport = transport;
    }

    String describe() { return transport.describe(); }

    /** RFCOMM server (service UUID): the watch connects directly to the phone. */
    static Transport rfcomm(final Context ctx) {
        return new Transport() {
            private volatile BluetoothServerSocket server;

            @Override public Conn open() throws IOException {
                BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
                BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
                if (ad == null || !ad.isEnabled()) throw new IOException("BT apagado");
                final BluetoothSocket s;
                try {
                    BluetoothServerSocket ss = ad.listenUsingInsecureRfcommWithServiceRecord(
                            "SHMBridgeComms", RFCOMM_UUID);
                    server = ss;
                    Log.i(TAG, "link: RFCOMM escuchando");
                    try {
                        s = ss.accept();  // blocking; abort() cuts it
                    } finally {
                        server = null;
                        try { ss.close(); } catch (IOException ignored) {}
                    }
                } catch (SecurityException e) {
                    throw new IOException("missing BLUETOOTH_CONNECT", e);
                }
                String name;
                try { name = s.getRemoteDevice().getName(); } catch (SecurityException e) { name = "?"; }
                final String who = name;
                return new Conn() {
                    @Override public InputStream in() throws IOException { return s.getInputStream(); }
                    @Override public OutputStream out() throws IOException { return s.getOutputStream(); }
                    @Override public void close() throws IOException { s.close(); }
                    @Override public String describe() { return "RFCOMM " + who; }
                };
            }
            @Override public void abort() {
                BluetoothServerSocket ss = server;
                if (ss != null) try { ss.close(); } catch (IOException ignored) {}
            }
            @Override public String describe() { return "bt rfcomm"; }
        };
    }

    synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "bridge-comms-link");
        worker.setDaemon(true);
        worker.start();
    }

    synchronized void stop() {
        running = false;
        transport.abort();
        closeSocket();
        if (worker != null) worker.interrupt();
        worker = null;
    }

    boolean isUp() { return up && ready; }

    /** Milliseconds since the last received frame (pings included); -1 if none ever arrived. */
    long lastRxAgeMs() {
        long t = lastFrameAt;
        return t == 0 ? -1 : Math.max(0, System.currentTimeMillis() - t);
    }

    /** Queues a frame; false if there is no link or the queue is full (does not block).
     *  Before verification only hello should be sent (onLinkUp does it). */
    boolean send(String path, byte[] data) {
        if (!up) return false;
        byte[] frame = encode(path, data);
        if (frame == null) return false;
        return outq.offer(frame);
    }

    static byte[] encode(String path, byte[] data) {
        byte[] p = path.getBytes(StandardCharsets.UTF_8);
        int dlen = data == null ? 0 : data.length;
        if (p.length > 0xFFFF || 2L + p.length + dlen > MAX_FRAME) return null;
        byte[] f = new byte[6 + p.length + dlen];
        int len = 2 + p.length + dlen;
        f[0] = (byte) (len >>> 24); f[1] = (byte) (len >>> 16);
        f[2] = (byte) (len >>> 8); f[3] = (byte) len;
        f[4] = (byte) (p.length >>> 8); f[5] = (byte) p.length;
        System.arraycopy(p, 0, f, 6, p.length);
        if (dlen > 0) System.arraycopy(data, 0, f, 6 + p.length, dlen);
        return f;
    }

    private void loop() {
        long backoff = 1000;
        while (running) {
            boolean served = false;
            try {
                Conn c = transport.open();
                socket = c;
                if (!running) break;
                served = serve(c);
            } catch (IOException e) {
                Log.d(TAG, "link: no connection " + transport.describe() + " (" + e.getMessage() + ")");
            } finally {
                closeSocket();
            }
            if (!running) break;
            // a session that got to receive frames resets the back-off
            backoff = served ? 1000 : Math.min(backoff * 2, 30000);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** true if at least one frame from the peer arrived (verified link). */
    private boolean serve(Conn s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.in()));
        final DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.out()));
        lastRx = SystemClock.uptimeMillis();
        outq.clear();
        Thread writer = new Thread(() -> writeLoop(out), "bridge-comms-writer");
        writer.setDaemon(true);
        boolean verified = false;
        String reason = "EOF";
        try {
            // the writer starts before the first read: the initial ping verifies us to the host
            up = true;
            writer.start();
            Log.i(TAG, "link: connected (" + s.describe() + ")");
            while (running) {
                int len = in.readInt();
                if (len < 2 || len > MAX_FRAME) {
                    reason = "desync len=" + len;
                    break;
                }
                int plen = in.readUnsignedShort();
                if (plen > len - 2) {
                    reason = "desync pathLen=" + plen;
                    break;
                }
                byte[] pb = new byte[plen];
                in.readFully(pb);
                byte[] db = new byte[len - 2 - plen];
                if (db.length > 0) in.readFully(db);
                lastRx = SystemClock.uptimeMillis();
                lastFrameAt = System.currentTimeMillis();
                if (!verified) {
                    // the link counts as up only after the first received frame from the peer
                    verified = true;
                    ready = true;
                    Log.i(TAG, "link: peer verified");
                    listener.onLinkUp();
                }
                if (plen == 0) continue;  // bare heartbeat: not part of the channel, ignored
                String path = new String(pb, StandardCharsets.UTF_8);
                if (PING.equals(path)) continue;
                if (!path.startsWith(PREFIX)) continue;  // outside the channel: ignored
                listener.onFrame(path, db);
            }
        } catch (SocketTimeoutException e) {
            reason = "no traffic in " + READ_TIMEOUT_MS + " ms";
        } catch (IOException e) {
            reason = staleSince() > READ_TIMEOUT_MS ? "no traffic in " + READ_TIMEOUT_MS + " ms"
                    : e.getClass().getSimpleName();
        } finally {
            boolean wasUp = up;
            up = false;
            ready = false;
            outq.clear();
            outq.offer(STOP);
            writer.interrupt();
            Log.i(TAG, "link: closed (" + reason + ")" + (verified ? "" : " no verified peer"));
            if (wasUp && verified) listener.onLinkDown();
        }
        return verified;
    }

    private void writeLoop(DataOutputStream out) {
        byte[] ping = encode(PING, null);
        try {
            out.write(ping);
            out.flush();
            while (up) {
                byte[] f = outq.poll(PING_MS, TimeUnit.MILLISECONDS);
                if (f == STOP) break;
                if (staleSince() > READ_TIMEOUT_MS) {
                    // RFCOMM has no soTimeout: closing unblocks the reader
                    Log.i(TAG, "link: no traffic in " + READ_TIMEOUT_MS + " ms; closing");
                    closeSocket();
                    return;
                }
                out.write(f == null ? ping : f);
                // drain whatever is already queued before the flush
                byte[] more;
                while ((more = outq.poll()) != null) {
                    if (more == STOP) { out.flush(); return; }
                    out.write(more);
                }
                out.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            Log.i(TAG, "link: escritura: " + e.getClass().getSimpleName());
            closeSocket();
        }
    }

    /** Awake time without a frame from the watch. Uptime stops while this phone is suspended, so
     *  waking up after a long sleep (e.g. to forward a notification) does not read as a dead link. */
    private long staleSince() { return SystemClock.uptimeMillis() - lastRx; }

    private void closeSocket() {
        Conn s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
