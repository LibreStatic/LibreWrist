package shminterop.shmbridge.watch;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Watch side of the {@code /__bridge__/} channel (contract: docs/bridge-comms-protocol.md).
 *
 * <p>Receives the channel's frames from {@link FrameMux} (RFCOMM session) or injected by
 * debugging ({@code DEBUG_RECV}), and dispatches them on its own thread to
 * {@link BridgeNotifier}, {@link AlarmMirror} and {@link CallMirror}. Sends to the phone
 * go out on another thread (an RFCOMM write may block) as a complete mux frame.</p>
 *
 * <p>Privacy: only path, id, package and lengths go in the log; never bodies,
 * names or numbers.</p>
 */
public final class BridgeHub {

    public static final String TAG = "SHMBridgeComms";
    static final String P = FrameMux.BRIDGE_PREFIX;
    /** Cap on JSON per frame from the contract. */
    static final int MAX_JSON = 64 * 1024;
    /** A peer hello that arrives within this window after ours is taken as a
     *  reply and is not answered (avoids hello ping-pong whatever the other
     *  end's policy). */
    static final long HELLO_ECHO_WINDOW_MS = 3000;

    private static volatile BridgeHub instance;

    public static BridgeHub get(Context ctx) {
        BridgeHub h = instance;
        if (h == null) {
            synchronized (BridgeHub.class) {
                h = instance;
                if (h == null) {
                    h = new BridgeHub(ctx.getApplicationContext());
                    instance = h;
                }
            }
        }
        return h;
    }

    private final Context ctx;
    private final ExecutorService inbound = Executors.newSingleThreadExecutor(named("bridge-hub-in"));
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(named("bridge-hub-out"));
    private volatile FrameMux mux;
    private volatile long lastHelloSentAt;
    public volatile boolean peerHello;
    public volatile String peerRole;

    private BridgeHub(Context ctx) {
        this.ctx = ctx;
        try { BridgeNotifier.ensureFixedChannels(ctx); } catch (Throwable t) { Log.w(TAG, "canales: " + t); }
    }

    public boolean isLinked() { FrameMux m = mux; return m != null && !m.isClosed(); }

    // ------------------------------------------------------------------ mux events

    void onSessionOpen(FrameMux m) {
        mux = m;
        peerHello = false;
        Log.i(TAG, "session open: sending hello");
        sendHello();
    }

    void onSessionClosed(FrameMux m, String reason) {
        if (mux == m) mux = null;
        peerHello = false;
        Log.i(TAG, "session closed: " + reason);
    }

    void onBridgeFrame(FrameMux m, final String path, final byte[] data) {
        dispatchAsync(path, data, false);
    }

    /** Debugging: injects a frame as if it came from the phone. */
    public void inject(String path, String json) {
        byte[] d = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        dispatchAsync(path, d, true);
    }

    private void dispatchAsync(final String path, final byte[] data, final boolean debug) {
        inbound.execute(new Runnable() {
            @Override public void run() {
                try {
                    dispatch(path, data, debug);
                } catch (Throwable t) {
                    Log.w(TAG, "despacho " + path + " len=" + data.length + ": " + t);
                }
            }
        });
    }

    private void dispatch(String path, byte[] data, boolean debug) throws Exception {
        if (FrameMux.PING_PATH.equals(path)) return; // channel keepalive: no log
        Log.i(TAG, (debug ? "rx(debug) " : "rx ") + path + " len=" + data.length);
        if (data.length > MAX_JSON) {
            Log.w(TAG, "descartada " + path + ": len=" + data.length + " > " + MAX_JSON);
            return;
        }
        if (!path.startsWith(P)) {
            Log.w(TAG, "path outside the channel: " + path);
            return;
        }
        String sub = path.substring(P.length());
        JSONObject j = data.length == 0 ? new JSONObject()
                : new JSONObject(new String(data, StandardCharsets.UTF_8));
        switch (sub) {
            case "hello":
                onPeerHello(j);
                break;
            case "notif/post":
                BridgeNotifier.post(ctx, j);
                break;
            case "notif/remove":
                BridgeNotifier.remove(ctx, j.optString("id", ""), j.optString("why", ""));
                break;
            case "notif/sync":
                JSONArray ids = j.optJSONArray("ids");
                java.util.Set<String> keep = new java.util.HashSet<>();
                if (ids != null) for (int i = 0; i < ids.length(); i++) keep.add(ids.optString(i));
                BridgeNotifier.sync(ctx, keep);
                break;
            case "alarm/next":
                AlarmMirror.setNext(ctx, j.isNull("t") ? null : j.optLong("t", 0));
                break;
            case "alarm/ring":
                AlarmMirror.onPhoneRing(ctx, j.optString("id", ""), str(j, "label"),
                        j.optBoolean("canSnooze", true));
                break;
            case "alarm/stop":
                AlarmMirror.onPhoneStop(ctx, j.optString("id", ""));
                break;
            case "call/state":
                CallMirror.onState(ctx, j.optString("s", ""), str(j, "name"));
                break;
            default:
                Log.i(TAG, "unhandled path: " + path);
        }
    }

    private void onPeerHello(JSONObject j) {
        peerRole = j.optString("role", "?");
        boolean first = !peerHello;
        peerHello = true;
        Log.i(TAG, "peer hello: role=" + peerRole + " v=" + j.optInt("v", 0)
                + " caps=" + j.optJSONArray("caps"));
        CallMirror.flushPulled(this);
        // The channel state (notif/sync, alarm/next) is resent by the phone on receiving
        // our hello. We answer its hello unless it is an echo of ours.
        if (System.currentTimeMillis() - lastHelloSentAt > HELLO_ECHO_WINDOW_MS) {
            sendHello();
        } else if (first) {
            Log.i(TAG, "peer hello taken as a reply to ours");
        }
    }

    private void sendHello() {
        try {
            JSONObject h = new JSONObject();
            h.put("v", 1);
            h.put("role", "watch");
            JSONArray caps = new JSONArray();
            caps.put("notif");
            caps.put("alarm");
            caps.put("call");
            h.put("caps", caps);
            lastHelloSentAt = System.currentTimeMillis();
            send("hello", h);
        } catch (Exception e) {
            Log.w(TAG, "hello: " + e);
        }
    }

    // ------------------------------------------------------------------ send

    /** Sends {@code /__bridge__/<sub>} (or a full path) to the phone, in the background. */
    public void send(String subOrPath, JSONObject json) {
        send(subOrPath, json == null ? "{}" : json.toString());
    }

    public void send(String subOrPath, final String json) {
        final String path = subOrPath.startsWith(P) ? subOrPath : P + subOrPath;
        final byte[] d = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
        if (d.length > MAX_JSON) {
            Log.w(TAG, "tx descartada " + path + ": len=" + d.length + " > " + MAX_JSON);
            return;
        }
        outbound.execute(new Runnable() {
            @Override public void run() {
                FrameMux m = mux;
                if (m == null || m.isClosed()) {
                    Log.i(TAG, "tx without link, dropped: " + path + " len=" + d.length);
                    return;
                }
                try {
                    m.sendBridge(path, d);
                    Log.i(TAG, "tx " + path + " len=" + d.length);
                } catch (Exception e) {
                    Log.w(TAG, "tx " + path + " failed: " + e);
                }
            }
        });
    }

    /** Optional string: null if missing, JSON null or empty (optString would give "null"). */
    static String str(JSONObject j, String k) {
        if (j == null || j.isNull(k)) return null;
        String v = j.optString(k, null);
        return v == null || v.isEmpty() ? null : v;
    }

    static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (Exception ignored) {}
        return o;
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }
}
