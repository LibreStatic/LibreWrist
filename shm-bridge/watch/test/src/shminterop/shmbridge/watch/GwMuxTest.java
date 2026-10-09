package shminterop.shmbridge.watch;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host (JVM) tests of the mux's GW channel: routing of {@code /__gw__/*} (prefix stripped,
 * never to 8773), {@code sendGw} framing, interleaving with SHM and bridge, the
 * {@code /__d__/*} protocol of the DataClient ({@link GwData}) and protobuf DataMap
 * vectors ({@link GwDataMap}). No JUnit: main() runs each test and prints PASS/FAIL
 * (like FrameMuxTest). The "phone" is a loopback socket, the "SHM" a ServerSocket.
 */
public final class GwMuxTest {

    static final Random RND = new Random(0x6d1);
    static final boolean VERBOSE = System.getenv("MUX_TEST_VERBOSE") != null;

    // ------------------------------------------------------------------ doubles

    /** Inline GW handler (the mini-hub of the /__d__/* tests). */
    interface GwHandler { void onGw(String path, byte[] data); }

    /** Session like FrameMuxTest.Session, but with the GW handler invoked inline. */
    static final class GwSession {
        final Socket phone;
        final Socket muxSide;
        final FrameMux mux;
        final List<String> gwPaths = new CopyOnWriteArrayList<>();
        final List<byte[]> gwDatas = new CopyOnWriteArrayList<>();
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final Thread thread;

        GwSession(FrameMuxTest.FakeShm shm, final GwHandler handler) throws Exception {
            ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            muxSide = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
            phone = ss.accept();
            ss.close();
            phone.setSoTimeout(10000);
            mux = new FrameMux(muxSide.getInputStream(), muxSide.getOutputStream(), muxSide,
                    shm, new FrameMux.Listener() {
                @Override public void onSessionOpen(FrameMux m) { opened.countDown(); }
                @Override public void onBridgeFrame(FrameMux m, String path, byte[] data) {}
                @Override public void onGwFrame(FrameMux m, String path, byte[] data) {
                    gwPaths.add(path);
                    gwDatas.add(data);
                    if (handler != null) handler.onGw(path, data);
                }
                @Override public void onLocalState(FrameMux m, boolean attached) {}
                @Override public void onSessionClosed(FrameMux m, String reason) { closed.countDown(); }
            }, new FrameMux.Logger() {
                @Override public void log(String msg) { if (VERBOSE) System.out.println("    [mux] " + msg); }
            }).timings(1000, 40);
            thread = new Thread(new Runnable() {
                @Override public void run() { mux.run(); }
            }, "gw-mux-run");
            thread.setDaemon(true);
            thread.start();
            check(opened.await(5, TimeUnit.SECONDS), "onSessionOpen");
        }

        void close() {
            try { phone.close(); } catch (IOException ignored) {}
            try { thread.join(5000); } catch (InterruptedException ignored) {}
        }
    }

    /** Mini-hub of the /__d__/* protocol: the same store logic as GwHub.dispatchData. */
    static final class MiniHub implements GwHandler {
        final GwData.Store store = new GwData.Store();
        final List<byte[]> snapshots = new CopyOnWriteArrayList<>();
        volatile FrameMux mux;

        @Override public void onGw(String path, byte[] data) {
            String dp = GwData.dataPath(path);
            if (dp == null) return;
            if (GwData.PATH_PUT.equals(dp)) {
                String uri = GwData.decodeUri(data);
                if (uri != null) store.put(uri, GwData.decodeDataAfterUri(data));
            } else if (GwData.PATH_DEL.equals(dp)) {
                String uri = GwData.decodeUri(data);
                if (uri != null) store.delete(uri);
            } else if (GwData.PATH_ALL.equals(dp)) {
                byte[] snap = GwData.encodeAllResponse(store.snapshot());
                snapshots.add(snap);
                try {
                    mux.sendGw("/" + GwData.PATH_ALL_R, snap);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilities

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    static byte[] randomData(int max) {
        byte[] d = new byte[RND.nextInt(max + 1)];
        RND.nextBytes(d);
        return d;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Reads from the phone the next frame that is not a keepalive; null if the deadline expires
     *  (iterative: the mux keepalives are frequent and recursion would not terminate). */
    static FrameMux.Frame nextFrame(DataInputStream in, long deadlineMs) throws IOException {
        long end = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < end) {
            try {
                FrameMux.Frame f = FrameMux.readFrame(in);
                if (f == null) throw new AssertionError("EOF from the phone");
                if (f.isHeartbeat() || FrameMux.PING_PATH.equals(f.path())) continue;
                return f;
            } catch (SocketTimeoutException e) {
                return null;
            }
        }
        return null;
    }

    static FrameMux.Frame awaitFrame(DataInputStream in, String path) throws IOException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            FrameMux.Frame f = nextFrame(in, end - System.currentTimeMillis());
            if (f == null) return null;
            if (path.equals(f.path())) return f;
        }
        return null;
    }

    static void awaitSize(List<?> l, int n) {
        for (int i = 0; i < 300 && l.size() < n; i++) sleep(10);
        check(l.size() >= n, "expected " + n + " items, got " + l.size());
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    /** Writes an all-r item ([short ulen][uri][int dlen][data]) and returns the new offset. */
    static int putItem(byte[] b, int off, String uri, byte[] data) {
        byte[] u = uri.getBytes(StandardCharsets.UTF_8);
        b[off] = (byte) (u.length >>> 8);
        b[off + 1] = (byte) u.length;
        System.arraycopy(u, 0, b, off + 2, u.length);
        off += 2 + u.length;
        putInt(b, off, data.length);
        System.arraycopy(data, 0, b, off + 4, data.length);
        return off + 4 + data.length;
    }

    // ------------------------------------------------------------------ GW tests of the mux

    /** /__gw__/* reaches the listener with the prefix stripped (in order, data intact) and never
     *  8773; /__bridge__/* still goes to the channel; SHM passes byte for byte; sendGw arrives prefixed. */
    static void testGwRoutingAndPassthrough() throws Exception {
        FrameMuxTest.FakeShm shm = new FrameMuxTest.FakeShm(true);
        FrameMuxTest.Session s = new FrameMuxTest.Session(shm, 2500, 50);
        try {
            Socket shmSock = shm.next();
            check(FrameMuxTest.awaitTrue(s.rec.localStates, true), "8773 side attached");
            List<String> expGwPaths = new ArrayList<>();
            List<byte[]> expGwData = new ArrayList<>();
            List<String> expBridgePaths = new ArrayList<>();
            ByteArrayOutputStream shmExpect = new ByteArrayOutputStream();
            for (int i = 0; i < 500; i++) {
                int kind = RND.nextInt(3);
                if (kind == 0) {
                    String full = FrameMuxTest.GW_PATHS[RND.nextInt(FrameMuxTest.GW_PATHS.length)];
                    byte[] d = randomData(1500);
                    s.phone.getOutputStream().write(FrameMux.encode(full, d));
                    expGwPaths.add(full.substring(FrameMux.GW_PREFIX.length()));
                    expGwData.add(d);
                } else if (kind == 1) {
                    String p = FrameMuxTest.BRIDGE_PATHS[RND.nextInt(FrameMuxTest.BRIDGE_PATHS.length)];
                    byte[] d = randomData(1500);
                    s.phone.getOutputStream().write(FrameMux.encode(p, d));
                    expBridgePaths.add(p);
                } else {
                    byte[] f = FrameMuxTest.shmFrame(true);
                    s.phone.getOutputStream().write(f);
                    shmExpect.write(f);
                }
            }
            s.phone.getOutputStream().flush();
            byte[] got = new byte[shmExpect.size()];
            shmSock.setSoTimeout(15000);
            FrameMuxTest.readFully(shmSock.getInputStream(), got);
            check(Arrays.equals(got, shmExpect.toByteArray()),
                    "identical bytes to the SHM (" + got.length + " B)");
            awaitSize(s.rec.gwPaths, expGwPaths.size());
            awaitSize(s.rec.paths, expBridgePaths.size());
            sleep(150); // margin to detect extra routing
            check(s.rec.gwPaths.equals(expGwPaths), "GW paths without prefix and in order ("
                    + s.rec.gwPaths.size() + ")");
            for (int i = 0; i < expGwData.size(); i++) {
                check(Arrays.equals(s.rec.gwDatas.get(i), expGwData.get(i)), "data GW #" + i);
            }
            check(s.rec.paths.equals(expBridgePaths), "bridge stays on the bridge channel ("
                    + s.rec.paths.size() + " vs " + expBridgePaths.size() + ")");
            // nothing GW reached the SHM: the readFully above consumed exactly the expected
            // bytes; an extra read must time out
            shmSock.setSoTimeout(300);
            try {
                int extra = shmSock.getInputStream().read();
                check(extra < 0, "extra bytes towards the SHM");
            } catch (SocketTimeoutException expected) { /* ok */ }
            check(s.mux.framesToGw == expGwPaths.size(), "framesToGw counter");
            // sendGw -> phone with exact prefix (double slash, full GMS path)
            byte[] d = randomData(400);
            s.mux.sendGw("/__d__/all-r", d);
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            FrameMux.Frame f = awaitFrame(in, "/__gw__//__d__/all-r");
            check(f != null, "prefixed all-r reached the phone");
            check(Arrays.equals(f.data(), d), "sendGw data intact");
            check(s.mux.framesFromGw >= 1, "framesFromGw counter");
            check(!s.mux.isClosed(), "session alive");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** sendGw validates the path: no slash, already prefixed or with bridge prefix -> exception;
     *  a valid path is delivered even if no SHM is linked. */
    static void testSendGwGuards() throws Exception {
        FrameMuxTest.FakeShm shm = new FrameMuxTest.FakeShm(false);
        FrameMuxTest.Session s = new FrameMuxTest.Session(shm, 2500, 50);
        try {
            String[][] bad = {
                    {null, "null"}, {"__d__/all-r", "no slash"},
                    {"/__gw__/__d__/put", "already prefixed"}, {"/__bridge__/hello", "bridge prefix"},
                    {"", "empty"},
            };
            for (String[] b : bad) {
                boolean threw = false;
                try {
                    s.mux.sendGw(b[0], new byte[0]);
                } catch (IllegalArgumentException expected) {
                    threw = true;
                }
                check(threw, "sendGw accepted an invalid path: " + b[1]);
            }
            s.mux.sendGw("/c_ram/resultRamInfo", new byte[]{1});
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            FrameMux.Frame f = awaitFrame(in, "/__gw__//c_ram/resultRamInfo");
            check(f != null, "valid sendGw delivered (double slash on the wire)");
            check(Arrays.equals(f.data(), new byte[]{1}), "data of the valid path");
        } finally {
            s.close();
            shm.close();
        }
    }

    /** Concurrent writes sendGw + sendBridge + local SHM: the framing holds and each
     *  channel keeps its order. */
    static void testGwInterleaving() throws Exception {
        FrameMuxTest.FakeShm shm = new FrameMuxTest.FakeShm(true);
        final FrameMuxTest.Session s = new FrameMuxTest.Session(shm, 2500, 50);
        try {
            final Socket shmSock = shm.next();
            check(FrameMuxTest.awaitTrue(s.rec.localStates, true), "enlazado");
            final int writers = 3, perWriter = 120, shmFrames = 80;
            final List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
            List<Thread> ts = new ArrayList<>();
            ts.add(new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        OutputStream so = shmSock.getOutputStream();
                        for (int i = 0; i < shmFrames; i++) {
                            FrameMuxTest.writeChunked(so, FrameMuxTest.shmFrame(false));
                        }
                    } catch (Throwable e) { errs.add(e); }
                }
            }));
            for (int t = 0; t < writers; t++) {
                final boolean gw = t % 2 == 0;
                final int tid = t;
                ts.add(new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            for (int i = 0; i < perWriter; i++) {
                                byte[] d = (tid + ":" + i).getBytes(StandardCharsets.UTF_8);
                                if (gw) s.mux.sendGw("/__d__/t" + tid, d);
                                else s.mux.sendBridge("/__bridge__/t" + tid, d);
                                if (i % 13 == 0) Thread.yield();
                            }
                        } catch (Throwable e) { errs.add(e); }
                    }
                }));
            }
            for (Thread t : ts) t.start();
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            int[] next = new int[writers];
            int seenShm = 0;
            int total = writers * perWriter + shmFrames;
            int seen = 0;
            while (seen < total) {
                FrameMux.Frame f = FrameMux.readFrame(in);
                check(f != null, "premature EOF at the phone");
                if (FrameMuxTest.isKeepalive(f)) continue;
                seen++;
                String p = f.path();
                if (p.startsWith("/__gw__/")) {
                    int tid = p.charAt(p.length() - 1) - '0';
                    check(Arrays.equals(f.data(), (tid + ":" + next[tid]).getBytes(StandardCharsets.UTF_8)),
                            "orden GW t" + tid + " en " + p);
                    next[tid]++;
                } else if (p.startsWith("/__bridge__/")) {
                    int tid = p.charAt(p.length() - 1) - '0';
                    check(Arrays.equals(f.data(), (tid + ":" + next[tid]).getBytes(StandardCharsets.UTF_8)),
                            "orden bridge t" + tid + " en " + p);
                    next[tid]++;
                } else {
                    seenShm++;
                }
            }
            for (Thread t : ts) t.join(15000);
            check(errs.isEmpty(), "errores en escritores: " + errs);
            check(seenShm == shmFrames, "all local SHM frames (" + seenShm + ")");
            check(next[0] == perWriter && next[1] == perWriter && next[2] == perWriter,
                    "all frames of the hubs");
        } finally {
            s.close();
            shm.close();
        }
    }

    // ------------------------------------------------------------------ __d__ protocol

    /** put/del/all conversation over a real session: the store ends with the right items
     *  and the returned all-r has the exact bytes of the format (hand-built vector). */
    static void testDataProtocol() throws Exception {
        FrameMuxTest.FakeShm shm = new FrameMuxTest.FakeShm(false);
        final MiniHub hub = new MiniHub();
        GwSession s = new GwSession(shm, hub);
        hub.mux = s.mux;
        try {
            OutputStream po = s.phone.getOutputStream();
            byte[] d1 = {1, 2, 3};
            byte[] d2 = {};
            String u1 = "wear://watch/x/a";
            String u2 = "wear://watch/x/b";
            po.write(FrameMux.encode("/__gw__/__d__/put", GwData.encodeUriPayload(u1, d1)));
            po.write(FrameMux.encode("/__gw__/__d__/put", GwData.encodeUriPayload(u2, d2)));
            po.write(FrameMux.encode("/__gw__/__d__/all", new byte[0]));
            po.flush();
            DataInputStream in = new DataInputStream(s.phone.getInputStream());
            FrameMux.Frame f = awaitFrame(in, "/__gw__//__d__/all-r");
            check(f != null, "all-r arrived");
            check(hub.store.size() == 2, "store with 2 items");
            check(u1.equals(hub.store.snapshot().get(0).uri), "store ordenado: item a");
            check(Arrays.equals(hub.store.snapshot().get(0).data, d1), "data of item a");
            check(Arrays.equals(hub.store.snapshot().get(1).data, d2), "data of item b");
            // exact all-r: [int n][short ulen uri][int dlen data]...
            byte[] expect = new byte[4 + (2 + u1.getBytes(StandardCharsets.UTF_8).length + 4 + 3)
                    + (2 + u2.getBytes(StandardCharsets.UTF_8).length + 4 + 0)];
            putInt(expect, 0, 2);
            putItem(expect, 4, u1, d1);
            putItem(expect, 4 + 2 + u1.getBytes(StandardCharsets.UTF_8).length + 4 + 3, u2, d2);
            check(Arrays.equals(f.data(), expect), "exact all-r bytes ("
                    + f.data().length + " vs " + expect.length + " B)");
            check(hub.snapshots.size() == 1 && Arrays.equals(hub.snapshots.get(0), expect),
                    "store snapshot equals the all-r");
            // del + reduced snapshot
            po.write(FrameMux.encode("/__gw__/__d__/del", GwData.encodeUriPayload(u1, null)));
            po.write(FrameMux.encode("/__gw__/__d__/all", new byte[0]));
            po.flush();
            f = awaitFrame(in, "/__gw__//__d__/all-r");
            check(f != null, "second all-r");
            check(hub.store.size() == 1, "del removed the item");
            byte[] expect2 = new byte[4 + 2 + u2.getBytes(StandardCharsets.UTF_8).length + 4];
            putInt(expect2, 0, 1);
            putItem(expect2, 4, u2, d2);
            check(Arrays.equals(f.data(), expect2), "all-r after del");
            // malformed put: no store corruption or session drop
            po.write(FrameMux.encode("/__gw__/__d__/put", new byte[]{0, 9, 1, 2}));
            po.write(FrameMux.encode("/__gw__/__d__/all", new byte[0]));
            po.flush();
            f = awaitFrame(in, "/__gw__//__d__/all-r");
            check(f != null, "session alive after malformed put");
            check(hub.store.size() == 1, "malformed put did not touch the store");
            check(s.gwPaths.contains("__d__/put") && s.gwPaths.contains("__d__/del")
                    && s.gwPaths.contains("__d__/all"), "paths seen without prefix");
        } finally {
            s.close();
            shm.close();
        }
    }

    // ------------------------------------------------------------------ codec + DataMap

    static void testGwDataCodec() {
        byte[] payload = GwData.encodeUriPayload("wear://watch/u/ñ", new byte[]{9, 8});
        check("wear://watch/u/ñ".equals(GwData.decodeUri(payload)), "uri with UTF-8");
        check(Arrays.equals(GwData.decodeDataAfterUri(payload), new byte[]{9, 8}), "data after uri");
        byte[] sinData = GwData.encodeUriPayload("u", null);
        check("u".equals(GwData.decodeUri(sinData)) && GwData.decodeDataAfterUri(sinData).length == 0,
                "put without data");
        check(GwData.decodeUri(new byte[]{0, 5, 1, 2}) == null, "truncated uri -> null");
        check(GwData.decodeUri(new byte[0]) == null, "empty payload -> null");
        check(GwData.decodeUri(new byte[]{0, 9, 1}) == null, "ulen exceeded -> null");
        check("__d__/put".equals(GwData.dataPath("/__d__/put")), "dataPath with slash");
        check("__d__/put".equals(GwData.dataPath("__d__/put")), "dataPath without slash");
        check(GwData.dataPath("d/put") == null, "d/put is not a DataClient path");
        check(GwData.dataPath("hello") == null, "path ajeno -> null");
        check("__d__/all-r".equals(GwData.dataPath("__d__/all-r")), "all-r recognized");
        // all-r roundtrip + truncated
        List<GwData.Item> items = new ArrayList<>();
        items.add(new GwData.Item("u/1", new byte[]{1}));
        items.add(new GwData.Item("u/2", new byte[0]));
        List<GwData.Item> back = GwData.decodeAllResponse(GwData.encodeAllResponse(items));
        check(back != null && back.size() == 2 && "u/1".equals(back.get(0).uri)
                && Arrays.equals(back.get(0).data, new byte[]{1})
                && "u/2".equals(back.get(1).uri) && back.get(1).data.length == 0, "all-r roundtrip");
        check(GwData.decodeAllResponse(new byte[3]) == null, "short all-r -> null");
        byte[] full = GwData.encodeAllResponse(items);
        byte[] truncado = Arrays.copyOf(full, full.length - 1);
        check(GwData.decodeAllResponse(truncado) == null, "truncated all-r -> null");
        // store: replace, delete, order and validation
        GwData.Store st = new GwData.Store();
        st.put("b", new byte[]{2});
        st.put("a", new byte[]{1});
        st.put("b", new byte[]{22});
        st.delete("a");
        st.put("c", new byte[0]);
        check(st.size() == 2 && "b".equals(st.snapshot().get(0).uri)
                && st.snapshot().get(0).data[0] == 22 && "c".equals(st.snapshot().get(1).uri),
                "store: replace/borrar/orden");
        boolean threw = false;
        try {
            st.put("", new byte[0]);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "store rejects empty uri");
    }

    /** Hand-built byte vector of the format verified in hp-full (u.smali +
     *  wearable_oem/{f0,d,c,b,a}): {a=3(int), b="x", c=true, d=42(long)}. */
    static void testGwDataMapVectors() {
        GwDataMap dm = new GwDataMap()
                .putInt("a", 3)
                .putString("b", "x")
                .putBoolean("c", true)
                .putLong("d", 42L);
        byte[] expect = {
                // entry a: elen=0x0B; value = 08 06 (tag 6) 12 02 (len) 30 03 (field 6, varint 3)
                0x0A, 0x0B, 0x0A, 0x01, 'a', 0x12, 0x06, 0x08, 0x06, 0x12, 0x02, 0x30, 0x03,
                // entry b: elen=0x0C; value = 08 02 (tag 2) 12 03 12 01 'x'
                0x0A, 0x0C, 0x0A, 0x01, 'b', 0x12, 0x07, 0x08, 0x02, 0x12, 0x03, 0x12, 0x01, 'x',
                // entry c: elen=0x0B; value = 08 08 (tag 8) 12 02 40 01
                0x0A, 0x0B, 0x0A, 0x01, 'c', 0x12, 0x06, 0x08, 0x08, 0x12, 0x02, 0x40, 0x01,
                // entry d: elen=0x0B; value = 08 05 (tag 5) 12 02 18 2A
                0x0A, 0x0B, 0x0A, 0x01, 'd', 0x12, 0x06, 0x08, 0x05, 0x12, 0x02, 0x28, 0x2A,
        };
        byte[] out = dm.build();
        check(Arrays.equals(out, expect), "exact DataMap bytes (" + out.length + " vs "
                + expect.length + " B)");
        Map<String, Object> back = GwDataMap.decode(out);
        check(Integer.valueOf(3).equals(back.get("a")), "decode int");
        check("x".equals(back.get("b")), "decode string");
        check(Boolean.TRUE.equals(back.get("c")), "decode bool");
        check(Long.valueOf(42L).equals(back.get("d")), "decode long");
    }

    /** Roundtrip of the remaining supported types, including negatives (canonical 10-byte
     *  varint), double/float (fixed LE), byte[] and empty string. */
    static void testGwDataMapTypes() {
        GwDataMap dm = new GwDataMap()
                .putInt("i", -1)
                .putLong("l", -2L)
                .putDouble("dob", 1.5d)
                .putFloat("f", 1.5f)
                .putByteArray("by", new byte[]{(byte) 0xAB, 0x00})
                .putString("empty", "")
                .putString("utf8", "ñé");
        byte[] out = dm.build();
        // int32 -1 -> field 6 (0x30) + sign-extended 10-byte varint: FF*9 01
        int i = 0;
        while (i < out.length && out[i] != 0x30) i++;
        check(i + 10 < out.length && out[i] == 0x30 && out[i + 1] == (byte) 0xff
                && out[i + 9] == (byte) 0xff && out[i + 10] == 0x01, "negative int32 = 10-byte varint");
        Map<String, Object> back = GwDataMap.decode(out);
        check(Integer.valueOf(-1).equals(back.get("i")), "roundtrip negative int");
        check(Long.valueOf(-2L).equals(back.get("l")), "roundtrip negative long");
        check(Double.valueOf(1.5d).equals(back.get("dob")), "roundtrip double");
        check(Float.valueOf(1.5f).equals(back.get("f")), "roundtrip float");
        check(Arrays.equals(new byte[]{(byte) 0xAB, 0x00}, (byte[]) back.get("by")), "roundtrip bytes");
        check("".equals(back.get("empty")), "empty string");
        check("ñé".equals(back.get("utf8")), "UTF-8 string");
        // keys are sorted even if inserted in reverse (like f0.c with TreeSet)
        GwDataMap sorted = new GwDataMap().putString("z", "1").putString("a", "2");
        byte[] so = sorted.build();
        check(so[0] == 0x0A && so[4] == 'a' && so[so.length - 1] == '1', "claves ordenadas");
    }

    /** The decoder skips unknown fields by wire type (forward-safe with other versions
     *  of the proto: varint, fixed64, bytes, fixed32) and keeps the known ones. */
    static void testGwDataMapUnknownSkip() throws IOException {
        // inside the Value: unknown fields (3 fixed64, 5 varint, 7 bytes) around
        // the known ones (1 = tag, 2 = payload)
        byte[] value = new byte[]{
                0x19, 1, 2, 3, 4, 5, 6, 7, 8,   // field 3 fixed64 (unknown)
                0x08, 0x02,                      // field 1 (tag) = 2 (string)
                0x28, 0x09,                      // field 5 varint (unknown)
                0x12, 0x04, 0x12, 0x02, 0x61, 0x62, // field 2 (payload) = a{2: "ab"}
                0x3A, 0x01, 0x09,                // field 7 bytes (unknown)
        };
        ByteArrayOutputStream o2 = new ByteArrayOutputStream(32);
        o2.write(0x0A);                       // Entry (field 1 of the DataMap)
        o2.write(value.length + 5);          // key (3 B) + value header (2 B) + value
        o2.write(0x0A);
        o2.write(0x01);
        o2.write('w');
        o2.write(0x12);
        o2.write(value.length);
        o2.write(value, 0, value.length);
        // top: unknown fields (7 varint, 8 fixed64, 9 bytes, 12 fixed32) around
        ByteArrayOutputStream top = new ByteArrayOutputStream(64);
        top.write(0x38);
        top.write(5);                         // field 7 varint
        top.write(0x41);
        for (int i = 0; i < 8; i++) top.write(0x33); // field 8 fixed64
        top.write(0x4A);
        top.write(3);
        top.write(1);
        top.write(2);
        top.write(3);                         // campo 9 bytes
        top.write(0x65);
        top.write(1);
        top.write(2);
        top.write(3);
        top.write(4);                         // field 12 fixed32
        top.write(o2.toByteArray(), 0, o2.size());
        Map<String, Object> v2 = GwDataMap.decode(top.toByteArray());
        check("ab".equals(v2.get("w")), "known payload between unknown fields ("
                + v2 + ")");
        check(v2.size() == 1, "unknown ones do not create keys");
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
        run("routing GW: prefijo quitado, SHM intacto, sendGw prefijado", GwMuxTest::testGwRoutingAndPassthrough);
        run("sendGw rejects invalid paths", GwMuxTest::testSendGwGuards);
        run("GW + bridge + SHM interleaving without breaking framing", GwMuxTest::testGwInterleaving);
        run("__d__/* protocol: exact put/del/all/all-r", GwMuxTest::testDataProtocol);
        run("GwData codec: payloads, all-r and store", GwMuxTest::testGwDataCodec);
        run("GwDataMap: exact byte vector", GwMuxTest::testGwDataMapVectors);
        run("GwDataMap: type roundtrip and key order", GwMuxTest::testGwDataMapTypes);
        run("GwDataMap: decoder skips unknown fields", GwMuxTest::testGwDataMapUnknownSkip);
        System.out.println();
        System.out.println("GwMuxTest: " + pass + " PASS, " + fail + " FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }
}
