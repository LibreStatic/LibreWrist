package shminterop.shmbridge.watch;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Frame codec and session loop of the {@code /__bridge__/} channel over the RFCOMM session
 * (pure Java, no Android: tested on the host JVM with {@code shm-bridge/watch/test/run-tests.sh}).
 *
 * <p>Framing: {@code [int len][short pathLen][path][data]}, big-endian,
 * {@code len = 2 + pathLen + data.length}; an empty path ({@code pathLen=0}) is a bare heartbeat.</p>
 *
 * <ul>
 *   <li><b>RFCOMM &rarr; hub:</b> frames whose path starts with {@code /__bridge__/} go to the
 *   {@link Listener} (BridgeHub, in-process). Any other frame (bare heartbeats included) is
 *   dropped; the first one of a session is logged (path length only, never the path).</li>
 *   <li><b>hub &rarr; RFCOMM:</b> always complete frames under a single write lock.</li>
 *   <li>The session emits its own {@code /__bridge__/ping} keepalive if the RFCOMM has been idle
 *   for {@code heartbeatMs} (2 min), and answers every ping of the phone unless it wrote less
 *   than {@link #ANSWER_MIN_GAP_MS} before. The period is long on purpose: every frame from the
 *   phone wakes the watch CPU, and a seconds-scale keepalive kept it awake most of the night. A
 *   lost link is reported by the Bluetooth stack (the socket closes), not by the keepalive.</li>
 *   <li>If the RFCOMM dies or desynchronizes (len &lt; 2, len &gt; 16 MB or pathLen &gt; len-2) the
 *   session is closed. There are no read timeouts on the RFCOMM: a healthy session is never cut
 *   by silence.</li>
 * </ul>
 */
public final class FrameMux {

    public static final String BRIDGE_PREFIX = "/__bridge__/";
    private static final byte[] BRIDGE_PREFIX_BYTES = BRIDGE_PREFIX.getBytes(StandardCharsets.UTF_8);
    /** Desync ceiling: a larger len is not a real frame but garbage in the stream. */
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    // ------------------------------------------------------------------ interfaces

    /** Session events. Invoked from the mux threads: do not block. */
    public interface Listener {
        void onSessionOpen(FrameMux mux);
        void onBridgeFrame(FrameMux mux, String path, byte[] data);
        void onSessionClosed(FrameMux mux, String reason);
    }

    public interface Logger {
        void log(String msg);
    }

    /** Parsed frame: {@code raw} are the exact bytes read (header included). */
    public static final class Frame {
        public final byte[] raw;
        public final int pathLen;

        Frame(byte[] raw, int pathLen) { this.raw = raw; this.pathLen = pathLen; }

        public boolean isHeartbeat() { return pathLen == 0; }

        public boolean isBridge() {
            if (pathLen < BRIDGE_PREFIX_BYTES.length) return false;
            for (int i = 0; i < BRIDGE_PREFIX_BYTES.length; i++) {
                if (raw[6 + i] != BRIDGE_PREFIX_BYTES[i]) return false;
            }
            return true;
        }

        public String path() { return new String(raw, 6, pathLen, StandardCharsets.UTF_8); }

        public byte[] data() {
            int off = 6 + pathLen;
            byte[] d = new byte[raw.length - off];
            System.arraycopy(raw, off, d, 0, d.length);
            return d;
        }
    }

    /** Invalid frame: the stream lost framing and there is no way to resynchronize. */
    public static final class DesyncException extends IOException {
        DesyncException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ codec

    /** Reads a complete frame; {@code null} on clean EOF between frames. */
    public static Frame readFrame(DataInputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int len = (b0 << 24) | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (len < 2 || len > MAX_FRAME) throw new DesyncException("len=" + len);
        byte[] raw = new byte[4 + len];
        raw[0] = (byte) (len >>> 24);
        raw[1] = (byte) (len >>> 16);
        raw[2] = (byte) (len >>> 8);
        raw[3] = (byte) len;
        in.readFully(raw, 4, len);
        int plen = ((raw[4] & 0xff) << 8) | (raw[5] & 0xff);
        if (plen > len - 2) throw new DesyncException("pathLen=" + plen + " len=" + len);
        return new Frame(raw, plen);
    }

    public static byte[] encode(String path, byte[] data) {
        byte[] p = path == null ? new byte[0] : path.getBytes(StandardCharsets.UTF_8);
        if (p.length > 0xFFFF) throw new IllegalArgumentException("path too long");
        int dlen = data == null ? 0 : data.length;
        long len = 2L + p.length + dlen;
        if (len > MAX_FRAME) throw new IllegalArgumentException("frame too large");
        byte[] raw = new byte[(int) (4 + len)];
        int l = (int) len;
        raw[0] = (byte) (l >>> 24);
        raw[1] = (byte) (l >>> 16);
        raw[2] = (byte) (l >>> 8);
        raw[3] = (byte) l;
        raw[4] = (byte) (p.length >>> 8);
        raw[5] = (byte) p.length;
        System.arraycopy(p, 0, raw, 6, p.length);
        if (dlen > 0) System.arraycopy(data, 0, raw, 6 + p.length, dlen);
        return raw;
    }

    /** The channel's own keepalive: an empty {@code /__bridge__/ping}. It is never a bare
     *  heartbeat (len=2, pathLen=0), which is not part of this channel. */
    static final String PING_PATH = "/__bridge__/ping";
    static final byte[] HEARTBEAT = encode(PING_PATH, new byte[0]);
    /** Idle period of our own keepalive. */
    static final long HEARTBEAT_MS = 120_000;
    /** A phone ping is answered unless we wrote this recently (it already saw our traffic). */
    static final long ANSWER_MIN_GAP_MS = 1000;

    // ------------------------------------------------------------------ session

    private final InputStream remoteIn;
    private final OutputStream remoteOut;
    private final Closeable remoteCloser;
    private final Listener listener;
    private final Logger logger;

    private volatile long heartbeatMs = HEARTBEAT_MS;
    /** Wall clock (it keeps counting while the CPU is suspended); replaceable in tests. */
    private volatile Clock clock = SYSTEM_CLOCK;

    private final Object writeLock = new Object();
    private volatile long lastRemoteWrite;      // 0 = never: the first keepalive goes out right away
    private volatile boolean closed;
    private volatile String closeReason;
    private Thread hbThread;
    private boolean loggedForeign;

    // counters (diagnostics and tests)
    public volatile long framesToHub, framesFromHub, framesDropped, heartbeatsSent;

    public FrameMux(InputStream remoteIn, OutputStream remoteOut, Closeable remoteCloser,
                    Listener listener, Logger logger) {
        this.remoteIn = remoteIn;
        this.remoteOut = remoteOut;
        this.remoteCloser = remoteCloser;
        this.listener = listener;
        this.logger = logger;
    }

    /** Time source for the keepalive bookkeeping (tests only). */
    public FrameMux clock(Clock clock) {
        this.clock = clock;
        return this;
    }

    public FrameMux timings(long heartbeatMs) {
        this.heartbeatMs = heartbeatMs;
        return this;
    }

    public boolean isClosed() { return closed; }

    /** Blocks until the RFCOMM session ends; always closes the session on exit. */
    public void run() {
        String reason = "EOF RFCOMM";
        hbThread = new Thread(new Runnable() {
            @Override public void run() { heartbeatLoop(); }
        }, "bridge-mux-hb");
        hbThread.setDaemon(true);
        hbThread.start();
        try { listener.onSessionOpen(this); } catch (Throwable t) { log("onSessionOpen: " + t); }
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(remoteIn, 16384));
            while (!closed) {
                Frame f = readFrame(in);
                if (f == null) break;
                answerIfDue(f);
                if (f.isBridge()) {
                    framesToHub++;
                    try {
                        listener.onBridgeFrame(this, f.path(), f.data());
                    } catch (Throwable t) {
                        log("hub: " + t);
                    }
                } else {
                    framesDropped++;
                    // Heartbeats (pathLen=0) are silent; anything else is logged once per
                    // session, length only (never the path).
                    if (!f.isHeartbeat() && !loggedForeign) {
                        loggedForeign = true;
                        log("frame outside the channel dropped (pathLen=" + f.pathLen + ")");
                    }
                }
            }
        } catch (DesyncException e) {
            reason = "RFCOMM desync (" + e.getMessage() + ")";
        } catch (EOFException e) {
            reason = "RFCOMM EOF mid-frame";
        } catch (IOException e) {
            reason = "RFCOMM: " + e;
        } catch (Throwable t) {
            reason = "error: " + t;
        } finally {
            close(reason);
            String r = closeReason;
            try { listener.onSessionClosed(this, r); } catch (Throwable t) { log("onSessionClosed: " + t); }
        }
    }

    /** Closes the session (idempotent). */
    public void close(String reason) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeReason = reason;
        }
        log("session closed: " + reason);
        try { remoteCloser.close(); } catch (Throwable ignored) {}
        if (hbThread != null) hbThread.interrupt();
    }

    /** Sends a frame of the communications channel to the peer (phone). */
    public void sendBridge(String path, byte[] data) throws IOException {
        if (path == null || !path.startsWith(BRIDGE_PREFIX)) {
            throw new IllegalArgumentException("path outside the channel: " + path);
        }
        writeRemote(encode(path, data));
        framesFromHub++;
    }

    private void writeRemote(byte[] frame) throws IOException {
        synchronized (writeLock) {
            if (closed) throw new IOException("session closed");
            try {
                remoteOut.write(frame);
                remoteOut.flush();
            } catch (IOException e) {
                close("RFCOMM write: " + e);
                throw e;
            }
            lastRemoteWrite = clock.now();
        }
    }

    /**
     * Answers a phone ping, or any frame when our keepalive is overdue. While the watch CPU is
     * suspended the heartbeat thread's sleep stands still (it runs on the monotonic clock), so
     * the phone would see no traffic and drop the link; its ping wakes the watch, and answering
     * it here keeps the session up through Doze and battery saver. Answering every ping (not only
     * an overdue one) also keeps a phone bridge with a shorter watchdog satisfied.
     */
    private void answerIfDue(Frame f) {
        long idle = clock.now() - lastRemoteWrite;
        boolean ping = f.pathLen == PING_PATH.length() && PING_PATH.equals(f.path());
        if (idle < (ping ? ANSWER_MIN_GAP_MS : heartbeatMs)) return;
        try {
            writeRemote(HEARTBEAT);
            heartbeatsSent++;
        } catch (IOException ignored) {
            // writeRemote already closed the session; the read loop ends on its own
        }
    }

    private void heartbeatLoop() {
        while (!closed) {
            long idle = clock.now() - lastRemoteWrite;
            long hb = heartbeatMs;
            try {
                if (idle >= hb) {
                    writeRemote(HEARTBEAT);
                    heartbeatsSent++;
                } else {
                    Thread.sleep(Math.max(10, hb - idle));
                }
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                return; // writeRemote already closed the session
            }
        }
    }

    /** Milliseconds time source. */
    public interface Clock { long now(); }

    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    private void log(String m) {
        if (logger != null) try { logger.log(m); } catch (Throwable ignored) {}
    }
}
