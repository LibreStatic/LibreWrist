package shminterop.shmbridge.watch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host (JVM) tests of {@link FrameMux}: the "phone" is a loopback socket standing in
 * for RFCOMM.
 * No JUnit: main() runs each test and prints PASS/FAIL.
 */
public final class FrameMuxTest {

    static final boolean VERBOSE = System.getenv("MUX_TEST_VERBOSE") != null;
    static final Random RND = new Random(0x5b1d);

    // ------------------------------------------------------------------ doubles

    static final class Rec implements FrameMux.Listener {
        final List<String> paths = new CopyOnWriteArrayList<>();
        final List<byte[]> datas = new CopyOnWriteArrayList<>();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        volatile String closeReason;

        @Override public void onSessionOpen(FrameMux mux) { opened.countDown(); }
        @Override public void onBridgeFrame(FrameMux mux, String path, byte[] data) {
            datas.add(data);
            paths.add(path);
        }
        @Override public void onSessionClosed(FrameMux mux, String reason) {
            closeReason = reason;
            closed.countDown();
        }
    }

    /** Test session: mux running on its thread with a pair of sockets as RFCOMM. */
    static final class Session {
        final Socket phone;     // "phone" end of the RFCOMM
        final Socket muxSide;
        final FrameMux mux;
        final Rec rec = new Rec();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final Thread thread;

        Session(long hbMs) throws Exception {
            ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            muxSide = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
            phone = ss.accept();
            ss.close();
            phone.setSoTimeout(10000);
            mux = new FrameMux(muxSide.getInputStream(), muxSide.getOutputStream(), muxSide,
                    rec, new FrameMux.Logger() {
                        @Override public void log(String m) {
                            logs.add(m);
                            if (VERBOSE) System.out.println("    [mux] " + m);
                        }
                    }).timings(hbMs);
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

    /** Bare heartbeat or the channel's own keepalive (/__bridge__/ping). */
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

    /** Paths outside the channel (relay-era SHM paths, double-slash paths, near-miss prefixes). */
    static final String[] FOREIGN_PATHS = {
            "/shealthmonitor/ecg/sync", "/shealthmonitor/bp/data", "/capability/x",
            "/other/__d__/put", "/other//c_battery/sync",
            "/__channel__/abc", "/__bridgeX/not-the-channel", "/__bridge_/neither", "/a"};
    static final String[] BRIDGE_PATHS = {
            "/__bridge__/hello", "/__bridge__/notif/post", "/__bridge__/notif/remove",
            "/__bridge__/alarm/next", "/__bridge__/call/state"};

    static byte[] anyPathFrame(boolean allowHeartbeat) {
        int kind = RND.nextInt(10);
        if (allowHeartbeat && kind == 0) return FrameMux.encode("", null);
        if (kind == 1) return FrameMux.encode(FOREIGN_PATHS[RND.nextInt(FOREIGN_PATHS.length)], randomData(3000));
        if (kind == 2) return FrameMux.encode(BRIDGE_PATHS[RND.nextInt(BRIDGE_PATHS.length)], randomData(3000));
        int max = RND.nextInt(20) == 0 ? 200000 : 4000;
        return FrameMux.encode(FOREIGN_PATHS[RND.nextInt(FOREIGN_PATHS.length)], randomData(max));
    }

    static byte[] foreignFrame(boolean allowHeartbeat) {
        if (allowHeartbeat && RND.nextInt(5) == 0) return FrameMux.encode("", null);
        int max = RND.nextInt(20) == 0 ? 200000 : 4000;
        return FrameMux.encode(FOREIGN_PATHS[RND.nextInt(FOREIGN_PATHS.length)], randomData(max));
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
            check(f.isHeartbeat() == p.isEmpty(), "isHeartbeat() of " + p);
        }
        check(FrameMux.readFrame(in) == null, "clean EOF at the end");
    }

    /** Channel frames reach the hub in order even when they arrive split; every other frame
     *  (foreign paths, bare heartbeats) is dropped without breaking the session. */
    static void testBridgeRoutingAndForeignDrop() throws Exception {
        Session s = new Session(2500);
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            List<String> expPaths = new ArrayList<>();
            List<byte[]> expData = new ArrayList<>();
            int foreign = 0;
            for (int i = 0; i < 800; i++) {
                if (RND.nextInt(3) == 0) {
                    String p = BRIDGE_PATHS[RND.nextInt(BRIDGE_PATHS.length)];
                    byte[] d = randomData(2000);
                    stream.write(FrameMux.encode(p, d));
                    expPaths.add(p);
                    expData.add(d);
                } else {
                    stream.write(foreignFrame(true));
                    foreign++;
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
            w.join(30000);
            for (int i = 0; i < 300 && s.mux.framesToHub + s.mux.framesDropped < expPaths.size() + foreign; i++) sleep(20);
            check(s.rec.paths.equals(expPaths), "hub paths in order (" + s.rec.paths.size()
                    + "/" + expPaths.size() + ")");
            for (int i = 0; i < expData.size(); i++) {
                check(Arrays.equals(s.rec.datas.get(i), expData.get(i)), "hub data #" + i);
            }
            check(s.mux.framesDropped == foreign, "foreign frames dropped (" + s.mux.framesDropped
                    + "/" + foreign + ")");
            check(!s.mux.isClosed(), "session still alive");
        } finally {
            s.close();
        }
    }

    /** A non-channel frame is logged at most once per session, by length only (never the path). */
    static void testForeignDropLoggedOnceWithoutPath() throws Exception {
        Session s = new Session(2500);
        try {
            OutputStream po = s.phone.getOutputStream();
            for (int i = 0; i < 20; i++) po.write(FrameMux.encode("/secret/path/" + i, new byte[]{1}));
            po.write(FrameMux.encode("", null)); // bare heartbeat: silent
            po.write(FrameMux.encode("/__bridge__/hello", new byte[0]));
            po.flush();
            for (int i = 0; i < 200 && s.rec.paths.isEmpty(); i++) sleep(10);
            check(s.rec.paths.size() == 1, "hello reached the hub after the foreign frames");
            int foreignLogs = 0;
            for (String l : s.logs) {
                check(!l.contains("secret"), "log leaks a path: " + l);
                if (l.contains("outside the channel")) foreignLogs++;
            }
            check(foreignLogs == 1, "logged once per session (" + foreignLogs + ")");
            check(s.mux.framesDropped == 21, "dropped count " + s.mux.framesDropped);
        } finally {
            s.close();
        }
    }

    /** Concurrent hub writes never corrupt the framing and keep per-thread order. */
    static void testInterleavingWrites() throws Exception {
        final Session s = new Session(2500);
        try {
            final int hubThreads = 4, perThread = 150;
            final List<Throwable> errs = Collections.synchronizedList(new ArrayList<Throwable>());
            List<Thread> ts = new ArrayList<>();
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
            int[] next = new int[hubThreads];
            int total = hubThreads * perThread;
            int seen = 0;
            while (seen < total) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                check(f != null, "premature EOF at the phone");
                if (isKeepalive(f)) continue;
                seen++;
                check(f.isBridge(), "only channel frames go out");
                String p = f.path();
                int tid = Integer.parseInt(p.substring("/__bridge__/t".length()));
                String d = new String(f.data(), StandardCharsets.UTF_8);
                check(d.startsWith(tid + ":" + next[tid] + ":"), "hub order/content t" + tid);
                next[tid]++;
            }
            for (Thread t : ts) t.join(15000);
            check(errs.isEmpty(), "writer errors: " + errs);
        } finally {
            s.close();
        }
    }

    /** Own keepalive goes out when idle, the channel works both ways and sendBridge rejects
     *  paths outside the channel. */
    static void testKeepaliveAndChannel() throws Exception {
        Session s = new Session(100);
        try {
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            long t0 = System.currentTimeMillis();
            int hbs = 0;
            while (System.currentTimeMillis() - t0 < 700) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                check(f != null, "EOF while idle");
                check(Arrays.equals(f.raw, FrameMux.HEARTBEAT) && FrameMux.PING_PATH.equals(f.path()),
                        "exact keepalive (/__bridge__/ping, never a bare heartbeat)");
                hbs++;
            }
            check(hbs >= 4, "own keepalives (" + hbs + ")");
            OutputStream po = s.phone.getOutputStream();
            po.write(FrameMux.encode("/__bridge__/hello", "{\"v\":1}".getBytes(StandardCharsets.UTF_8)));
            po.flush();
            for (int i = 0; i < 100 && s.rec.paths.isEmpty(); i++) sleep(10);
            check(s.rec.paths.size() == 1 && s.rec.paths.get(0).equals("/__bridge__/hello"), "hello to the hub");
            s.mux.sendBridge("/__bridge__/notif/dismiss", "{\"id\":\"a\"}".getBytes(StandardCharsets.UTF_8));
            boolean got = false;
            for (int i = 0; i < 50 && !got; i++) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                got = f != null && "/__bridge__/notif/dismiss".equals(f.path());
            }
            check(got, "hub frame reaches the phone");
            check(!s.mux.isClosed(), "alive");
            for (String bad : new String[]{"/shealthmonitor/x", "/other//c_battery/x", null}) {
                boolean threw = false;
                try { s.mux.sendBridge(bad, null); } catch (IllegalArgumentException e) { threw = true; }
                check(threw, "sendBridge rejects " + bad);
            }
        } finally {
            s.close();
        }
    }

    /** RFCOMM desync: closes the whole session. */
    static void testRemoteDesync(byte[] garbage, String what) throws Exception {
        Session s = new Session(2500);
        try {
            OutputStream po = s.phone.getOutputStream();
            po.write(FrameMux.encode("/__bridge__/hello", new byte[0]));
            po.write(garbage);
            po.flush();
            check(s.rec.closed.await(5, TimeUnit.SECONDS), "session closed by " + what);
            check(s.rec.closeReason != null && s.rec.closeReason.contains("desync"),
                    "reason: " + s.rec.closeReason);
            s.thread.join(3000);
            check(!s.thread.isAlive(), "run() finished");
            check(seesEof(s.phone, 3000), "RFCOMM closed");
            check(s.rec.paths.size() == 1, "the valid frame before the garbage was delivered");
        } finally {
            s.close();
        }
    }

    /** If the RFCOMM dies the session is closed and sends fail. */
    static void testRemoteDeath() throws Exception {
        Session s = new Session(2500);
        try {
            s.phone.close();
            check(s.rec.closed.await(5, TimeUnit.SECONDS), "session closed");
            boolean threw = false;
            try { s.mux.sendBridge("/__bridge__/hello", null); } catch (IOException e) { threw = true; }
            check(threw, "sendBridge fails after close");
        } finally {
            s.close();
        }
    }

    /** A truncated frame followed by EOF closes the session (no hang). */
    static void testEofMidFrame() throws Exception {
        Session s = new Session(2500);
        try {
            byte[] f = FrameMux.encode("/__bridge__/hello", new byte[100]);
            s.phone.getOutputStream().write(f, 0, f.length / 2);
            s.phone.getOutputStream().flush();
            s.phone.shutdownOutput();
            check(s.rec.closed.await(5, TimeUnit.SECONDS), "session closed");
            check(s.rec.paths.isEmpty(), "truncated frame not delivered");
        } finally {
            s.close();
        }
    }

    /** A healthy, silent session is not cut (there is no read timeout on the RFCOMM). */
    static void testIdleSessionStaysUp() throws Exception {
        Session s = new Session(100);
        try {
            sleep(1200); // the "phone" sends nothing
            check(!s.mux.isClosed(), "idle session stays alive");
            check(s.mux.heartbeatsSent >= 8, "keepalives during the wait (" + s.mux.heartbeatsSent + ")");
        } finally {
            s.close();
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
        run("bridge routing in order, foreign frames dropped", FrameMuxTest::testBridgeRoutingAndForeignDrop);
        run("foreign drop logged once, without the path", FrameMuxTest::testForeignDropLoggedOnceWithoutPath);
        run("concurrent hub writes keep framing and order", FrameMuxTest::testInterleavingWrites);
        run("own keepalive and channel both ways", FrameMuxTest::testKeepaliveAndChannel);
        run("RFCOMM desync len<2", () -> testRemoteDesync(new byte[]{0, 0, 0, 1}, "len=1"));
        run("RFCOMM desync len=0", () -> testRemoteDesync(new byte[]{0, 0, 0, 0}, "len=0"));
        run("RFCOMM desync len>16MB", () -> testRemoteDesync(new byte[]{0x01, 0x00, 0x00, 0x01, 0, 0}, "len=16MB+1"));
        run("RFCOMM desync negative len", () -> testRemoteDesync(new byte[]{(byte) 0xff, 0, 0, 5, 0, 0}, "len<0"));
        run("RFCOMM desync pathLen>len-2", () -> testRemoteDesync(new byte[]{0, 0, 0, 4, 0, 9, 'a', 'b'}, "pathLen"));
        run("RFCOMM death closes the session", FrameMuxTest::testRemoteDeath);
        run("EOF mid-frame closes the session", FrameMuxTest::testEofMidFrame);
        run("idle session is not cut", FrameMuxTest::testIdleSessionStaysUp);
        System.out.println();
        System.out.println("FrameMuxTest: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
