package shminterop.shmbridge.watch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Host (JVM) tests of {@link FrameMux}: the "phone" is a loopback socket standing in
 * for RFCOMM, the "SHM" is a loopback ServerSocket standing in for 127.0.0.1:8773.
 * No JUnit: main() runs each test and prints PASS/FAIL.
 */
public final class FrameMuxTest {

    static final boolean VERBOSE = System.getenv("MUX_TEST_VERBOSE") != null;
    static final Random RND = new Random(0x5b1d);

    // ------------------------------------------------------------------ doubles

    static final class Rec implements FrameMux.Listener {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<byte[]> datas = new CopyOnWriteArrayList<>();
        final List<String> gwPaths = new CopyOnWriteArrayList<>();
        final List<byte[]> gwDatas = new CopyOnWriteArrayList<>();
        final BlockingQueue<Boolean> localStates = new LinkedBlockingQueue<>();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String closeReason;

        @Override public void onSessionOpen(FrameMux mux) { opened.countDown(); }
        @Override public void onBridgeFrame(FrameMux mux, String path, byte[] data) {
            datas.add(data);
            paths.add(path);
        }
        @Override public void onGwFrame(FrameMux mux, String path, byte[] data) {
            gwDatas.add(data);
            gwPaths.add(path);
        }
        @Override public void onLocalState(FrameMux mux, boolean attached) { localStates.add(attached); }
        @Override public void onSessionClosed(FrameMux mux, String reason) {
            closeReason = reason;
            closed.countDown();
        }
    }

    /** Fake "SHM" on loopback; {@code available=false} simulates the 8773 closed. */
    static final class FakeShm implements FrameMux.LocalConnector {
        final ServerSocket server;
        final BlockingQueue<Socket> accepted = new LinkedBlockingQueue<>();
        volatile boolean available;

        FakeShm(boolean available) throws IOException {
            this.available = available;
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (true) accepted.add(server.accept());
                    } catch (IOException ignored) {}
                }
            }, "fake-shm-accept");
            t.setDaemon(true);
            t.start();
        }

        @Override public FrameMux.Endpoint connect() throws IOException {
            if (!available) throw new ConnectException("8773 closed (simulated)");
            final Socket s = new Socket();
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 1000);
            return new FrameMux.Endpoint() {
                @Override public InputStream in() throws IOException { return s.getInputStream(); }
                @Override public OutputStream out() throws IOException { return s.getOutputStream(); }
                @Override public void close() throws IOException { s.close(); }
            };
        }

        Socket next() throws InterruptedException {
            Socket s = accepted.poll(5, TimeUnit.SECONDS);
            if (s == null) throw new AssertionError("the mux did not connect to the fake SHM");
            return s;
        }

        void close() { try { server.close(); } catch (IOException ignored) {} }
    }

    /** Test session: mux running on its thread with a pair of sockets as RFCOMM. */
    static final class Session {
        final Socket phone;     // "phone" end of the RFCOMM
        final Socket muxSide;
        final FrameMux mux;
        final Rec rec = new Rec();
        final Thread thread;

        Session(FakeShm shm, long hbMs, long retryMs) throws Exception {
            ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            muxSide = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
            phone = ss.accept();
            ss.close();
            phone.setSoTimeout(10000);
            mux = new FrameMux(muxSide.getInputStream(), muxSide.getOutputStream(), muxSide,
                    shm, rec, new FrameMux.Logger() {
                        @Override public void log(String m) { if (VERBOSE) System.out.println("    [mux] " + m); }
                    }).timings(hbMs, retryMs);
            thread = new Thread(new Runnable() {
                @Override public void run() { mux.run(); }
            }, "mux-run");
            thread.setDaemon(true);
            thread.start();
            check(rec.opened.await(5, TimeUnit.SECONDS), "onSessionOpen");
        }

        void close() {
            try { phone.close(); } catch (IOException ignored) {}
            try { thread.join(5000); } catch (InterruptedException ignored) {}
        }
    }

    // ------------------------------------------------------------------ utilities

    /** SHM heartbeat or the channel's own keepalive (/__bridge__/ping). */
    static boolean isKeepalive(FrameMux.Frame f) {
        return f.isHeartbeat() || FrameMux.PING_PATH.equals(f.path());
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    static byte[] randomData(int max) {
        byte[] d = new byte[RND.nextInt(max + 1)];
        RND.nextBytes(d);
        return d;
    }

    static final String[] SHM_PATHS = {
            "/shealthmonitor/ecg/sync", "/shealthmonitor/bp/data", "/capability/x",
            "/__channel__/abc", "/__bridgeX/not-the-channel", "/__bridge_/neither", "/a"};
    static final String[] BRIDGE_PATHS = {
            "/__bridge__/hello", "/__bridge__/notif/post", "/__bridge__/notif/remove",
            "/__bridge__/alarm/next", "/__bridge__/call/state"};
    static final String[] GW_PATHS = {
            "/__gw__/__d__/put", "/__gw__/__d__/all", "/__gw__//c_battery/syncBatteryInfo",
            "/__gw__//w_module_sec_watch_setting/battery_changed"};

    static byte[] anyPathFrame(boolean allowHeartbeat) {
        int kind = RND.nextInt(10);
        if (allowHeartbeat && kind == 0) return FrameMux.encode("", null);
        if (kind == 1) return FrameMux.encode(GW_PATHS[RND.nextInt(GW_PATHS.length)], randomData(3000));
        if (kind == 2) return FrameMux.encode(BRIDGE_PATHS[RND.nextInt(BRIDGE_PATHS.length)], randomData(3000));
        int max = RND.nextInt(20) == 0 ? 200000 : 4000;
        return FrameMux.encode(SHM_PATHS[RND.nextInt(SHM_PATHS.length)], randomData(max));
    }

    static byte[] shmFrame(boolean allowHeartbeat) {
        if (allowHeartbeat && RND.nextInt(5) == 0) return FrameMux.encode("", null);
        int max = RND.nextInt(20) == 0 ? 200000 : 4000;
        return FrameMux.encode(SHM_PATHS[RND.nextInt(SHM_PATHS.length)], randomData(max));
    }

    /** Writes in random chunks (1..37 bytes, sometimes large) with flush and pauses. */
    static void writeChunked(OutputStream out, byte[] all) throws IOException {
        int off = 0;
        while (off < all.length) {
            int n = RND.nextInt(8) == 0 ? 1 + RND.nextInt(9000) : 1 + RND.nextInt(37);
            n = Math.min(n, all.length - off);
            out.write(all, off, n);
            out.flush();
            off += n;
            if (RND.nextInt(50) == 0) sleep(1);
        }
    }

    static void readFully(InputStream in, byte[] buf) throws IOException {
        new DataInputStream(in).readFully(buf);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    static boolean awaitTrue(BlockingQueue<Boolean> q, boolean expected) throws InterruptedException {
        Boolean b = q.poll(5, TimeUnit.SECONDS);
        return b != null && b == expected;
    }

    /** Returns true if the socket sees EOF (or reset) before the deadline. */
    static boolean seesEof(Socket s, long ms) throws IOException {
        s.setSoTimeout((int) ms);
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            while (true) {
                if (in.read(buf) < 0) return true;
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true; // reset = closed
        }
    }

    // ------------------------------------------------------------------ tests

    /** readFrame over a stream that delivers 1..13 bytes per read: frames and bytes intact. */
    static void testCodecChunked() throws Exception {
        List<byte[]> frames = new ArrayList<>();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = 0; i < 600; i++) {
            byte[] f = anyPathFrame(true);
            frames.add(f);
            all.write(f);
        }
        final byte[] bytes = all.toByteArray();
        InputStream chunky = new InputStream() {
            int pos;
            @Override public int read() { return pos < bytes.length ? bytes[pos++] & 0xff : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= bytes.length) return -1;
                int n = Math.min(Math.min(len, 1 + RND.nextInt(13)), bytes.length - pos);
                System.arraycopy(bytes, pos, b, off, n);
                pos += n;
                return n;
            }
        };
        DataInputStream in = new DataInputStream(chunky);
        for (int i = 0; i < frames.size(); i++) {
            FrameMux.Frame f = FrameMux.readFrame(in);
            check(f != null, "frame " + i + " missing");
            check(Arrays.equals(f.raw, frames.get(i)), "frame " + i + " differs");
            String p = f.path();
            check(f.isBridge() == p.startsWith("/__bridge__/"), "isBridge() of " + p);
            check(f.isGw() == p.startsWith("/__gw__/"), "isGw() of " + p);
            check(f.isHeartbeat() == p.isEmpty(), "isHeartbeat() of " + p);
        }
        check(FrameMux.readFrame(in) == null, "clean EOF at the end");
    }

    /** RFCOMM→local: SHM frames byte-for-byte identical even if they arrive split; those of the
     *  channel go to the hub in order and never reach the SHM. */
    static void testPassthroughAndRouting() throws Exception {
        FakeShm shm = new FakeShm(true);
        Session s = new Session(shm, 2500, 50);
        try {
            Socket shmSock = shm.next();
            check(awaitTrue(s.rec.localStates, true), "8773 side attached");
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ByteArrayOutputStream expectShm = new ByteArrayOutputStream();
            List<String> expPaths = new ArrayList<>();
            List<byte[]> expData = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                if (RND.nextInt(3) == 0) {
                    String p = BRIDGE_PATHS[RND.nextInt(BRIDGE_PATHS.length)];
                    byte[] d = randomData(2000);
                    stream.write(FrameMux.encode(p, d));
                    expPaths.add(p);
                    expData.add(d);
                } else {
                    byte[] f = shmFrame(true);
                    stream.write(f);
                    expectShm.write(f);
                }
            }
            final byte[] all = stream.toByteArray();
            final OutputStream po = s.phone.getOutputStream();
            Thread w = new Thread(new Runnable() {
                @Override public void run() {
                    try { writeChunked(po, all); } catch (IOException e) { throw new RuntimeException(e); }
                }
            });
            w.start();
            byte[] got = new byte[expectShm.size()];
            shmSock.setSoTimeout(15000);
            readFully(shmSock.getInputStream(), got);
            w.join(15000);
            check(Arrays.equals(got, expectShm.toByteArray()), "identical bytes to the SHM ("
                    + got.length + " B)");
            for (int i = 0; i < 100 && s.rec.paths.size() < expPaths.size(); i++) sleep(20);
            check(s.rec.paths.equals(expPaths), "hub paths in order (" + s.rec.paths.size()
                    + "/" + expPaths.size() + ")");
            for (int i = 0; i < expData.size(); i++) {
                check(Arrays.equals(s.rec.datas.get(i), expData.get(i)), "hub data #" + i);
            }
            // nothing extra on the SHM
            shmSock.setSoTimeout(200);
            try {
                int extra = shmSock.getInputStream().read();
                check(extra < 0, "extra bytes towards the SHM");
            } catch (SocketTimeoutException expected) { /* ok */ }
            check(!s.mux.isClosed(), "session still alive");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** local→RFCOMM + hub→RFCOMM concurrent: the framing is never corrupted. */
    static void testInterleavingWrites() throws Exception {
        FakeShm shm = new FakeShm(true);
        final Session s = new Session(shm, 2500, 50);
        try {
            Socket shmSock = shm.next();
            check(awaitTrue(s.rec.localStates, true), "8773 side attached");
            final List<byte[]> shmFrames = new ArrayList<>();
            ByteArrayOutputStream shmStream = new ByteArrayOutputStream();
            for (int i = 0; i < 400; i++) {
                byte[] f = FrameMux.encode(SHM_PATHS[RND.nextInt(SHM_PATHS.length)], randomData(
                        RND.nextInt(30) == 0 ? 120000 : 3000));
                shmFrames.add(f);
                shmStream.write(f);
            }
            final byte[] shmAll = shmStream.toByteArray();
            final OutputStream so = shmSock.getOutputStream();
            final int hubThreads = 4, perThread = 150;
            final List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
            List<Thread> ts = new ArrayList<>();
            ts.add(new Thread(new Runnable() {
                @Override public void run() {
                    try { writeChunked(so, shmAll); } catch (Throwable e) { errs.add(e); }
                }
            }));
            for (int t = 0; t < hubThreads; t++) {
                final int tid = t;
                ts.add(new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            for (int i = 0; i < perThread; i++) {
                                byte[] d = (tid + ":" + i + ":" + "x".repeat(RND.nextInt(5000)))
                                        .getBytes(StandardCharsets.UTF_8);
                                s.mux.sendBridge("/__bridge__/t" + tid, d);
                                if (i % 17 == 0) Thread.yield();
                            }
                        } catch (Throwable e) { errs.add(e); }
                    }
                }));
            }
            for (Thread t : ts) t.start();
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            int shmIdx = 0;
            int[] next = new int[hubThreads];
            int total = shmFrames.size() + hubThreads * perThread;
            int seen = 0;
            while (seen < total) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                check(f != null, "premature EOF at the phone");
                if (isKeepalive(f)) continue;
                seen++;
                if (f.isBridge()) {
                    String p = f.path();
                    int tid = Integer.parseInt(p.substring("/__bridge__/t".length()));
                    String d = new String(f.data(), StandardCharsets.UTF_8);
                    check(d.startsWith(tid + ":" + next[tid] + ":"), "hub order/content t" + tid);
                    next[tid]++;
                } else {
                    check(Arrays.equals(f.raw, shmFrames.get(shmIdx)), "SHM frame #" + shmIdx + " identical");
                    shmIdx++;
                }
            }
            for (Thread t : ts) t.join(15000);
            check(errs.isEmpty(), "errores en escritores: " + errs);
            check(shmIdx == shmFrames.size(), "all SHM frames");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** Without 8773: the session stays alive, an own heartbeat goes out, the channel works and SHM
     *  frames are dropped without breaking anything. */
    static void testHeartbeatWithoutShm() throws Exception {
        FakeShm shm = new FakeShm(false);
        Session s = new Session(shm, 100, 30);
        try {
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            long t0 = System.currentTimeMillis();
            int hbs = 0;
            while (System.currentTimeMillis() - t0 < 700) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                check(f != null, "EOF without SHM");
                check(Arrays.equals(f.raw, FrameMux.HEARTBEAT) && FrameMux.PING_PATH.equals(f.path()), "exact keepalive (/__bridge__/ping, never an SHM heartbeat)");
                hbs++;
            }
            check(hbs >= 4, "own heartbeats (" + hbs + ")");
            OutputStream po = s.phone.getOutputStream();
            po.write(shmFrame(false));
            po.write(FrameMux.encode("/__bridge__/hello", "{\"v\":1}".getBytes(StandardCharsets.UTF_8)));
            po.flush();
            for (int i = 0; i < 100 && s.rec.paths.isEmpty(); i++) sleep(10);
            check(s.rec.paths.size() == 1 && s.rec.paths.get(0).equals("/__bridge__/hello"), "hello to the hub");
            check(s.mux.framesDroppedNoLocal == 1, "SHM frame dropped without 8773");
            s.mux.sendBridge("/__bridge__/notif/dismiss", "{\"id\":\"a\"}".getBytes(StandardCharsets.UTF_8));
            boolean got = false;
            for (int i = 0; i < 50 && !got; i++) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                got = f != null && "/__bridge__/notif/dismiss".equals(f.path());
            }
            check(got, "hub frame reaches the phone");
            check(!s.mux.isClosed() && !s.mux.isLocalAttached(), "alive and without 8773");
            boolean threw = false;
            try { s.mux.sendBridge("/shealthmonitor/x", null); } catch (IllegalArgumentException e) { threw = true; }
            check(threw, "sendBridge rejects paths outside the channel");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** The 8773 appears late and then drops: it hooks up, is released without taking down the RFCOMM and
     *  hooks up again. */
    static void testLateAttachAndLocalDeath() throws Exception {
        FakeShm shm = new FakeShm(false);
        Session s = new Session(shm, 2500, 40);
        try {
            sleep(200);
            check(!s.mux.isLocalAttached(), "no 8773 at the start");
            shm.available = true;
            Socket a = shm.next();
            check(awaitTrue(s.rec.localStates, true), "late hook-up");
            byte[] f1 = shmFrame(false);
            s.phone.getOutputStream().write(f1);
            s.phone.getOutputStream().flush();
            byte[] got = new byte[f1.length];
            a.setSoTimeout(5000);
            readFully(a.getInputStream(), got);
            check(Arrays.equals(got, f1), "frame after late hook-up");
            // SHM -> phone
            byte[] f2 = shmFrame(false);
            a.getOutputStream().write(f2);
            a.getOutputStream().flush();
            DataInputStream pin = new DataInputStream(s.phone.getInputStream());
            FrameMux.Frame r = FrameMux.readFrame(pin);
            while (r != null && isKeepalive(r)) r = FrameMux.readFrame(pin);
            check(r != null && Arrays.equals(r.raw, f2), "SHM frame -> phone");
            // the SHM dies mid-frame: the phone must not see stray bytes
            byte[] f3 = shmFrame(false);
            a.getOutputStream().write(f3, 0, Math.max(1, f3.length / 2));
            a.getOutputStream().flush();
            sleep(50);
            a.close();
            check(awaitTrue(s.rec.localStates, false), "8773 side dropped");
            check(!s.mux.isClosed(), "the RFCOMM stays alive after the 8773 drops");
            s.mux.sendBridge("/__bridge__/hello", "{}".getBytes(StandardCharsets.UTF_8));
            r = FrameMux.readFrame(pin);
            while (r != null && isKeepalive(r)) r = FrameMux.readFrame(pin);
            check(r != null && "/__bridge__/hello".equals(r.path()), "framing intact after truncated SHM frame");
            // re-hook
            Socket b = shm.next();
            check(awaitTrue(s.rec.localStates, true), "re-hook to the 8773");
            byte[] f4 = shmFrame(false);
            s.phone.getOutputStream().write(f4);
            s.phone.getOutputStream().flush();
            got = new byte[f4.length];
            b.setSoTimeout(5000);
            readFully(b.getInputStream(), got);
            check(Arrays.equals(got, f4), "frame to the new 8773");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** Garbage from the 8773: only that side is dropped. */
    static void testLocalDesyncDropsOnlyLocal() throws Exception {
        FakeShm shm = new FakeShm(true);
        Session s = new Session(shm, 2500, 40);
        try {
            Socket a = shm.next();
            check(awaitTrue(s.rec.localStates, true), "enlazado");
            a.getOutputStream().write(new byte[]{0x7f, 0x7f, 0x7f, 0x7f, 1, 2, 3});
            a.getOutputStream().flush();
            check(awaitTrue(s.rec.localStates, false), "desynchronized 8773 is dropped");
            check(!s.mux.isClosed(), "RFCOMM stays alive");
            check(seesEof(a, 3000), "the desynchronized 8773 is closed");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** RFCOMM desync: closes the whole session (RFCOMM and 8773). */
    static void testRemoteDesync(byte[] garbage, String what) throws Exception {
        FakeShm shm = new FakeShm(true);
        Session s = new Session(shm, 2500, 40);
        try {
            Socket a = shm.next();
            check(awaitTrue(s.rec.localStates, true), "enlazado");
            OutputStream po = s.phone.getOutputStream();
            po.write(shmFrame(false));
            po.write(garbage);
            po.flush();
            check(s.rec.closed.await(5, TimeUnit.SECONDS), "session closed by " + what);
            check(s.rec.closeReason != null && s.rec.closeReason.contains("desync"),
                    "motivo: " + s.rec.closeReason);
            s.thread.join(3000);
            check(!s.thread.isAlive(), "run() finished");
            check(seesEof(s.phone, 3000), "RFCOMM closed");
            // the 8773 received the first valid frame and then EOF
            check(seesEof(a, 3000), "8773 closed when the RFCOMM dies");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** If the RFCOMM dies both ends are closed. */
    static void testRemoteDeathKillsLocal() throws Exception {
        FakeShm shm = new FakeShm(true);
        Session s = new Session(shm, 2500, 40);
        try {
            Socket a = shm.next();
            check(awaitTrue(s.rec.localStates, true), "enlazado");
            s.phone.close();
            check(s.rec.closed.await(5, TimeUnit.SECONDS), "session closed");
            check(seesEof(a, 3000), "8773 closed when the RFCOMM dies");
            boolean threw = false;
            try { s.mux.sendBridge("/__bridge__/hello", null); } catch (IOException e) { threw = true; }
            check(threw, "sendBridge fails after close");
            sleep(150);
            check(shm.accepted.isEmpty(), "the 8773 is not re-hooked after closing the session");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** A healthy, silent session is not cut (there is no read timeout on the RFCOMM). */
    static void testIdleSessionStaysUp() throws Exception {
        FakeShm shm = new FakeShm(false);
        Session s = new Session(shm, 100, 40);
        try {
            sleep(1200); // the "phone" sends nothing
            check(!s.mux.isClosed(), "idle session stays alive");
            check(s.mux.heartbeatsSent >= 8, "heartbeats during the wait (" + s.mux.heartbeatsSent + ")");
        } finally {
            s.close();
            shm.close();
        }
    }

    // ------------------------------------------------------------------ runner

    interface T { void run() throws Exception; }

    static int pass, fail;

    static void run(String name, T t) {
        long t0 = System.currentTimeMillis();
        try {
            t.run();
            pass++;
            System.out.println("PASS " + name + " (" + (System.currentTimeMillis() - t0) + " ms)");
        } catch (Throwable e) {
            fail++;
            System.out.println("FAIL " + name + ": " + e);
            if (VERBOSE) e.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) {
        run("codec: frames split at arbitrary boundaries", FrameMuxTest::testCodecChunked);
        run("RFCOMM->8773 byte for byte + routing to the hub", FrameMuxTest::testPassthroughAndRouting);
        run("interleaved SHM + hub writes without breaking framing", FrameMuxTest::testInterleavingWrites);
        run("no 8773: own heartbeat and channel alive", FrameMuxTest::testHeartbeatWithoutShm);
        run("late 8773, drop and re-hook", FrameMuxTest::testLateAttachAndLocalDeath);
        run("8773 desync drops only that side", FrameMuxTest::testLocalDesyncDropsOnlyLocal);
        run("RFCOMM desync len<2", () -> testRemoteDesync(new byte[]{0, 0, 0, 1}, "len=1"));
        run("RFCOMM desync len=0", () -> testRemoteDesync(new byte[]{0, 0, 0, 0}, "len=0"));
        run("RFCOMM desync len>16MB", () -> testRemoteDesync(new byte[]{0x01, 0x00, 0x00, 0x01, 0, 0}, "len=16MB+1"));
        run("RFCOMM desync negative len", () -> testRemoteDesync(new byte[]{(byte) 0xff, 0, 0, 5, 0, 0}, "len<0"));
        run("RFCOMM desync pathLen>len-2", () -> testRemoteDesync(new byte[]{0, 0, 0, 4, 0, 9, 'a', 'b'}, "pathLen"));
        run("RFCOMM death closes the 8773", FrameMuxTest::testRemoteDeathKillsLocal);
        run("idle session is not cut", FrameMuxTest::testIdleSessionStaysUp);
        System.out.println();
        System.out.println("FrameMuxTest: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
