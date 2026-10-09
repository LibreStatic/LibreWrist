package shminterop.shmbridge.watch;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Codec and mirror store of the relay's DataClient under the GW channel (phase 2: Galaxy Wearable +
 * heartplugin without GMS). Contract: the paths of these
 * frames are those that come AFTER the {@code /__gw__/} prefix, with their leading slash:
 *
 * <ul>
 *   <li>{@code /__d__/put}: {@code [short ulen][uri UTF-8][data]} — store it and
 *   emit a local changed event.</li>
 *   <li>{@code /__d__/del}: {@code [short ulen][uri]}.</li>
 *   <li>{@code /__d__/all}: empty; answered with {@code /__d__/all-r}.</li>
 *   <li>{@code /__d__/all-r}: {@code [int n]} items {@code {[short ulen][uri][int dlen][data]}}.</li>
 * </ul>
 *
 * <p>Pure Java, no Android: tested on the host JVM with
 * {@code shm-bridge/watch/test/run-tests.sh}. GwHub uses it as is; the same format is
 * implemented by the GW client of the phone-side relay (D2 mirror: the phone drops the snapshot,
 * the watch replies).</p>
 */
public final class GwData {

    /** DataClient control paths, normalized (no leading slash). */
    public static final String PATH_PUT = "__d__/put";
    public static final String PATH_DEL = "__d__/del";
    public static final String PATH_ALL = "__d__/all";
    public static final String PATH_ALL_R = "__d__/all-r";

    private static final byte[] EMPTY = new byte[0];

    private GwData() {}

    /** If {@code gwPath} (received without the /__gw__/ prefix, with or without leading slash) is a
     *  DataClient path, returns it normalized ({@code __d__/*}); otherwise {@code null}. */
    public static String dataPath(String gwPath) {
        String p = gwPath == null ? "" : gwPath;
        if (p.startsWith("/")) p = p.substring(1);
        return p.startsWith("__d__/") ? p : null;
    }

    // ------------------------------------------------------------------ codec put/del

    /** put/del payload: {@code [short ulen][uri][data]}. {@code data} may be null. */
    public static byte[] encodeUriPayload(String uri, byte[] data) {
        byte[] u = utf8(uri);
        if (u.length == 0 || u.length > 0xFFFF) {
            throw new IllegalArgumentException("empty or too long uri: " + u.length);
        }
        byte[] d = data == null ? EMPTY : data;
        byte[] out = new byte[2 + u.length + d.length];
        out[0] = (byte) (u.length >>> 8);
        out[1] = (byte) u.length;
        System.arraycopy(u, 0, out, 2, u.length);
        System.arraycopy(d, 0, out, 2 + u.length, d.length);
        return out;
    }

    /** Uri of a put/del payload; {@code null} if the payload is truncated or empty. */
    public static String decodeUri(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        int ulen = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        if (2 + ulen > payload.length) return null;
        return new String(payload, 2, ulen, StandardCharsets.UTF_8);
    }

    /** Data of a put payload (what follows the uri). Call only if {@link #decodeUri}
     *  returned a uri; throws if the payload is truncated. */
    public static byte[] decodeDataAfterUri(byte[] payload) {
        int ulen = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
        int off = 2 + ulen;
        byte[] d = new byte[payload.length - off];
        System.arraycopy(payload, off, d, 0, d.length);
        return d;
    }

    // ------------------------------------------------------------------ codec all-r

    /** Snapshot item: uri + data (the data belongs to the item, it is not copied). */
    public static final class Item {
        public final String uri;
        public final byte[] data;

        public Item(String uri, byte[] data) {
            this.uri = uri;
            this.data = data == null ? EMPTY : data;
        }
    }

    /** {@code [int n] {[short ulen][uri][int dlen][data]}} in the given order. */
    public static byte[] encodeAllResponse(List<Item> items) {
        int total = 4;
        for (Item it : items) {
            byte[] u = utf8(it.uri);
            if (u.length == 0 || u.length > 0xFFFF) {
                throw new IllegalArgumentException("empty or too long uri: " + it.uri);
            }
            total += 2 + u.length + 4 + it.data.length;
        }
        byte[] out = new byte[total];
        out[0] = (byte) (items.size() >>> 24);
        out[1] = (byte) (items.size() >>> 16);
        out[2] = (byte) (items.size() >>> 8);
        out[3] = (byte) items.size();
        int off = 4;
        for (Item it : items) {
            byte[] u = utf8(it.uri);
            out[off] = (byte) (u.length >>> 8);
            out[off + 1] = (byte) u.length;
            System.arraycopy(u, 0, out, off + 2, u.length);
            off += 2 + u.length;
            int dl = it.data.length;
            out[off] = (byte) (dl >>> 24);
            out[off + 1] = (byte) (dl >>> 16);
            out[off + 2] = (byte) (dl >>> 8);
            out[off + 3] = (byte) dl;
            System.arraycopy(it.data, 0, out, off + 4, dl);
            off += 4 + dl;
        }
        return out;
    }

    /** Decodes an all-r payload; {@code null} if truncated. For tests and for the
     *  mirror on the other side. */
    public static List<Item> decodeAllResponse(byte[] payload) {
        if (payload == null || payload.length < 4) return null;
        int n = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        List<Item> out = new ArrayList<>(Math.min(n, 1024));
        int off = 4;
        for (int i = 0; i < n; i++) {
            if (off + 2 > payload.length) return null;
            int ulen = ((payload[off] & 0xff) << 8) | (payload[off + 1] & 0xff);
            off += 2;
            if (off + ulen > payload.length) return null;
            String uri = new String(payload, off, ulen, StandardCharsets.UTF_8);
            off += ulen;
            if (off + 4 > payload.length) return null;
            int dl = ((payload[off] & 0xff) << 24) | ((payload[off + 1] & 0xff) << 16)
                    | ((payload[off + 2] & 0xff) << 8) | (payload[off + 3] & 0xff);
            off += 4;
            if (off + dl > payload.length) return null;
            byte[] d = new byte[dl];
            System.arraycopy(payload, off, d, 0, dl);
            off += dl;
            out.add(new Item(uri, d));
        }
        return off == payload.length ? out : null;
    }

    // ------------------------------------------------------------------ mirror store

    /** Mirror store of DataItems on the watch side: uri → data. Sorted by uri (TreeMap) for
     *  stable snapshots; the arrays belong to the store (not copied). */
    public static final class Store {
        private final TreeMap<String, byte[]> items = new TreeMap<>();

        public synchronized void put(String uri, byte[] data) {
            if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("empty uri");
            items.put(uri, data == null ? EMPTY : data);
        }

        /** {@code true} if there was an item with that uri. */
        public synchronized boolean delete(String uri) {
            return items.remove(uri) != null;
        }

        public synchronized int size() { return items.size(); }

        /** Snapshot in uri order (the same one {@code __d__/all-r} answers with). */
        public synchronized List<Item> snapshot() {
            List<Item> out = new ArrayList<>(items.size());
            for (TreeMap.Entry<String, byte[]> e : items.entrySet()) {
                out.add(new Item(e.getKey(), e.getValue()));
            }
            return out;
        }
    }

    private static byte[] utf8(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}
