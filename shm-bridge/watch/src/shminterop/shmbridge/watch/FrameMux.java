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
 * Frame multiplexer of SHM's relay over the RFCOMM session (pure Java, no Android:
 * tested on the host JVM with {@code shm-bridge/watch/test/run-tests.sh}).
 *
 * <p>Framing (same as the SHM relay's {@code RelayLink}): {@code [int len][short pathLen][path][data]},
 * big-endian, {@code len = 2 + pathLen + data.length}; heartbeat = {@code len=2, pathLen=0}.</p>
 *
 * <ul>
 *   <li><b>RFCOMM → local:</b> frames whose path starts with {@code /__bridge__/} go to the
 *   {@link Listener} (BridgeHub, in-process); those starting with {@code /__gw__/} go to the
 *   same {@link Listener} through another callback with the prefix already stripped (GwHub, phase 2);
 *   everything else (heartbeats included) is forwarded byte by byte to the SHM's 8773 if it is
 *   linked, or dropped if not.</li>
 *   <li><b>local → RFCOMM</b> and <b>hub → RFCOMM:</b> always complete frames under a single
 *   write lock, so a hub frame never ends up in the middle of an SHM one.</li>
 *   <li>The 8773 is <b>optional</b>: without SHM the session stays alive for the channel, the local
 *   link is retried in the background and an own heartbeat is emitted if the RFCOMM is idle for
 *   {@code heartbeatMs}. If the 8773 side dies, only that side is dropped.</li>
 *   <li>If the RFCOMM dies or desynchronizes (len &lt; 2, len &gt; 16 MB or pathLen &gt; len-2) the
 *   whole session is closed (RFCOMM and 8773). There are no read timeouts on the RFCOMM: a
 *   healthy session is never cut by silence.</li>
 * </ul>
 */
public final class FrameMux {

    public static final String BRIDGE_PREFIX = "/__bridge__/";
    private static final byte[] BRIDGE_PREFIX_BYTES = BRIDGE_PREFIX.getBytes(StandardCharsets.UTF_8);
    /** GW channel prefix (phase 2: Galaxy Wearable / heartplugin without GMS). On the RFCOMM the
     *  path travels as {@code /__gw__/} + GMS path, and since every GMS path starts with {@code /}, the
     *  wire gets a double slash ({@code /__gw__//c_battery/...}): the phone's GW client
     *  strips exactly {@code /__gw__/} and recovers the GMS path with its leading slash. */
    public static final String GW_PREFIX = "/__gw__/";
    private static final byte[] GW_PREFIX_BYTES = GW_PREFIX.getBytes(StandardCharsets.UTF_8);
    /** Desync ceiling: a larger len is not a real frame but garbage in the stream. */
    public static final int MAX_FRAME = 16 * 1024 * 1024;

    // ------------------------------------------------------------------ interfaces

    /** Duplex endpoint (TCP socket to 8773 on the watch; loopback socket in tests). */
    public interface Endpoint extends Closeable {
        InputStream in() throws IOException;
        OutputStream out() throws IOException;
    }

    /** Opens the local side (8773). Must fail fast if SHM is not listening. */
    public interface LocalConnector {
        Endpoint connect() throws IOException;
    }

    /** Session events. Invoked from the mux threads: do not block. */
    public interface Listener {
        void onSessionOpen(FrameMux mux);
        void onBridgeFrame(FrameMux mux, String path, byte[] data);
        /** GW channel frame with the {@code /__gw__/} prefix already stripped: {@code path} is the
         *  full GMS path (with leading slash) or a control path ({@code /__d__/*}). */
        void onGwFrame(FrameMux mux, String path, byte[] data);
        void onLocalState(FrameMux mux, boolean attached);
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

        public boolean isGw() {
            if (pathLen < GW_PREFIX_BYTES.length) return false;
            for (int i = 0; i < GW_PREFIX_BYTES.length; i++) {
                if (raw[6 + i] != GW_PREFIX_BYTES[i]) return false;
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
        if (p.length > 0xFFFF) throw new IllegalArgumentException("path demasiado largo");
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

    /** The channel's own keepalive. NOT the SHM heartbeat (len=2, pathLen=0): SHM's RelayLink
     *  takes any received frame as proof that the peer SHM is alive, so
     *  the bridge never fabricates heartbeats for it; it uses an empty {@code /__bridge__/ping}, which a gateway
     *  diverts to the channel and never reaches the SHM. */
    static final String PING_PATH = "/__bridge__/ping";
    static final byte[] HEARTBEAT = encode(PING_PATH, new byte[0]);

    // ------------------------------------------------------------------ session

    private final InputStream remoteIn;
    private final OutputStream remoteOut;
    private final Closeable remoteCloser;
    private final LocalConnector connector;
    private final Listener listener;
    private final Logger logger;

    private volatile long heartbeatMs = 2500;
    private volatile long localRetryMs = 2000;

    private final Object writeLock = new Object();
    private final Object localWriteLock = new Object();
    private volatile long lastRemoteWrite;      // 0 = never: the first heartbeat goes out right away
    private volatile Endpoint local;
    private volatile boolean closed;
    private volatile String closeReason;
    private Thread hbThread;
    private Thread localThread;

    // counters (diagnostics and tests)
    public volatile long framesToLocal, framesFromLocal, framesToHub, framesFromHub,
            framesToGw, framesFromGw, framesDroppedNoLocal, heartbeatsSent;

    public FrameMux(InputStream remoteIn, OutputStream remoteOut, Closeable remoteCloser,
                    LocalConnector connector, Listener listener, Logger logger) {
        this.remoteIn = remoteIn;
        this.remoteOut = remoteOut;
        this.remoteCloser = remoteCloser;
        this.connector = connector;
        this.listener = listener;
        this.logger = logger;
    }

    public FrameMux timings(long heartbeatMs, long localRetryMs) {
        this.heartbeatMs = heartbeatMs;
        this.localRetryMs = localRetryMs;
        return this;
    }

    public boolean isClosed() { return closed; }

    public boolean isLocalAttached() { return local != null; }

    /** Blocks until the RFCOMM session ends; always closes both sides on exit. */
    public void run() {
        String reason = "EOF RFCOMM";
        hbThread = new Thread(new Runnable() {
            @Override public void run() { heartbeatLoop(); }
        }, "bridge-mux-hb");
        hbThread.setDaemon(true);
        localThread = new Thread(new Runnable() {
            @Override public void run() { localLoop(); }
        }, "bridge-mux-local");
        localThread.setDaemon(true);
        hbThread.start();
        localThread.start();
        try { listener.onSessionOpen(this); } catch (Throwable t) { log("onSessionOpen: " + t); }
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(remoteIn, 16384));
            while (!closed) {
                Frame f = readFrame(in);
                if (f == null) break;
                if (f.isBridge()) {
                    framesToHub++;
                    try {
                        listener.onBridgeFrame(this, f.path(), f.data());
                    } catch (Throwable t) {
                        log("hub: " + t);
                    }
                } else if (f.isGw()) {
                    framesToGw++;
                    try {
                        listener.onGwFrame(this, f.path().substring(GW_PREFIX.length()), f.data());
                    } catch (Throwable t) {
                        log("gw: " + t);
                    }
                } else {
                    forwardToLocal(f);
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

    /** Closes the whole session (idempotent). */
    public void close(String reason) {
        synchronized (this) {
            if (closed) return;
            closed = true;
            closeReason = reason;
        }
        log("session closed: " + reason);
        try { remoteCloser.close(); } catch (Throwable ignored) {}
        Endpoint l = local;
        local = null;
        if (l != null) try { l.close(); } catch (Throwable ignored) {}
        if (hbThread != null) hbThread.interrupt();
        if (localThread != null) localThread.interrupt();
    }

    /** Sends a frame of the communications channel to the peer (phone). */
    public void sendBridge(String path, byte[] data) throws IOException {
        if (path == null || !path.startsWith(BRIDGE_PREFIX)) {
            throw new IllegalArgumentException("path outside the channel: " + path);
        }
        writeRemote(encode(path, data));
        framesFromHub++;
    }

    /** Sends a GW channel frame to the peer (phone). {@code path} goes WITHOUT the prefix
     *  {@code /__gw__/} and with the leading slash of the GMS or control path (e.g.
     *  {@code /__d__/all-r}): on the RFCOMM {@code /__gw__/} + path goes out (double slash when
     *  the path already starts with {@code /}), which is what the phone's GW client expects
     *  to recover the exact path by stripping the prefix. */
    public void sendGw(String path, byte[] data) throws IOException {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("GW path without leading slash: " + path);
        }
        if (path.startsWith(GW_PREFIX) || path.startsWith(BRIDGE_PREFIX)) {
            throw new IllegalArgumentException("GW path with channel prefix: " + path);
        }
        writeRemote(encode(GW_PREFIX + path, data));
        framesFromGw++;
    }

    private void writeRemote(byte[] frame) throws IOException {
        synchronized (writeLock) {
            if (closed) throw new IOException("session closed");
            try {
                remoteOut.write(frame);
                remoteOut.flush();
            } catch (IOException e) {
                close("escritura RFCOMM: " + e);
                throw e;
            }
            lastRemoteWrite = System.currentTimeMillis();
        }
    }

    private void forwardToLocal(Frame f) {
        Endpoint l = local;
        if (l == null) {
            framesDroppedNoLocal++;
            return;
        }
        try {
            synchronized (localWriteLock) {
                OutputStream o = l.out();
                o.write(f.raw);
                o.flush();
            }
            framesToLocal++;
        } catch (IOException e) {
            detachLocal(l, "escritura 8773: " + e);
        }
    }

    private void detachLocal(Endpoint l, String why) {
        boolean was;
        synchronized (this) {
            was = local == l && l != null;
            if (was) local = null;
        }
        try { if (l != null) l.close(); } catch (Throwable ignored) {}
        if (was) {
            log("lado 8773 suelto: " + why);
            try { listener.onLocalState(this, false); } catch (Throwable ignored) {}
        }
    }

    private void heartbeatLoop() {
        while (!closed) {
            long idle = System.currentTimeMillis() - lastRemoteWrite;
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

    /** Hooks up the 8773 when it appears and pumps its frames to the RFCOMM; retries if it drops. */
    private void localLoop() {
        while (!closed) {
            Endpoint l;
            try {
                l = connector.connect();
            } catch (Throwable e) {
                if (!sleepRetry()) return;
                continue;
            }
            synchronized (this) {
                if (closed) {
                    try { l.close(); } catch (Throwable ignored) {}
                    return;
                }
                local = l;
            }
            log("lado 8773 enlazado");
            try { listener.onLocalState(this, true); } catch (Throwable ignored) {}
            String why = "EOF 8773";
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(l.in(), 16384));
                while (!closed) {
                    Frame f = readFrame(in);
                    if (f == null) break;
                    writeRemote(f.raw);
                    framesFromLocal++;
                }
            } catch (DesyncException e) {
                why = "8773 desync (" + e.getMessage() + ")";
            } catch (IOException e) {
                why = closed ? "session closed" : "8773: " + e;
            }
            detachLocal(l, why);
            if (!sleepRetry()) return;
        }
    }

    private boolean sleepRetry() {
        try {
            Thread.sleep(localRetryMs);
            return !closed;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void log(String m) {
        if (logger != null) try { logger.log(m); } catch (Throwable ignored) {}
    }
}
