package shminterop.relay;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

/** Receives the watch's nodeId from the SHM receivers (WearEventReceiver /
 *  PlainConnectionReceiver, which do receive MESSAGE_RECEIVED) and sends the
 *  feature_exchange with vendor=samsung through MessageApi.sendMessage (the only
 *  GMS path that works on some phones; getCapability is dead). */
public final class VendorReplyTrigger {

    private static final String TAG = "SHMRelay";
    private static final String PATH = "/wcs_extension/internal/feature";
    /** The watch's LocalNode (verified: ddb4a2ee). The nodeId that reaches the
     *  receivers is "relay-node" (synthetic), useless for GMS. */
    private static final String WATCH_NODE = "ddb4a2ee";
    private static volatile String sLastNodeId;
    private static volatile long sLastSend;

    private VendorReplyTrigger() {}

    /** Called from smali with the SourceNodeId of each watch message. */
    public static void onNodeSeen(String nodeId) {
        if (nodeId == null || nodeId.length() == 0) return;
        long now = System.currentTimeMillis();
        String last = sLastNodeId;
        if (nodeId.equals(last) && now - sLastSend < 30000) return;
        sLastNodeId = nodeId;
        sLastSend = now;
        final Context ctx = nodeContext();
        if (ctx == null) {
            Log.w(TAG, "vendor-override: no context (app not started)");
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { send(ctx); }
        }, "vendor-reply-send");
        t.setDaemon(true);
        t.start();
    }

    private static Context nodeContext() {
        try {
            Class<?> c = Class.forName("com.samsung.android.shealthmonitor.helper.a");
            java.lang.reflect.Field f = c.getDeclaredField("a");
            f.setAccessible(true);
            Object v = f.get(null);
            return (v instanceof Context) ? ((Context) v).getApplicationContext() : null;
        } catch (Throwable e) {
            Log.w(TAG, "vendor-override ctx: " + e);
            return null;
        }
    }

    /** Sends the samsung feature_exchange using the bridge's context
     *  (same wcs identity + watch node). */
    public static void sendFromBridge(Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { send(ctx); }
        }, "vendor-reply-bridge");
        t.setDaemon(true);
        t.start();
    }

    private static void send(Context ctx) {
        try {
            byte[] payload = buildPayload().getBytes("UTF-8");
            // wcs identity: GMS determines the sender's AppKey with
            // context.getPackageName() (not the process UID — Samsung's plugin
            // does the same from the watchmanager process). With this
            // identity the destination AppKey on the watch is the real wcs's and the
            // feature_exchange reaches the StatusInfoManager (which writes vendor).
            Context wcsCtx = new android.content.ContextWrapper(ctx) {
                @Override public String getPackageName() {
                    return "com.samsung.android.wcs.extension";
                }
                @Override public android.content.Context getApplicationContext() {
                    return this;  // keep GMS from unwrapping to the real app context
                }
            };
            MessageClient mc = Wearable.getMessageClient(wcsCtx);
            String node = (sLastNodeId == null || sLastNodeId.isEmpty()
                    || "relay-node".equals(sLastNodeId)) ? WATCH_NODE : sLastNodeId;
            // Fire-and-forget: the runtime's Task is a trimmed version without
            // addOnSuccessListener/addOnFailureListener. We log the attempt and
            // verify the real effect in the watch's wcs.
            mc.sendMessage(node, PATH, payload);
            Log.i(TAG, "feature_exchange samsung despachado a " + node
                    + " (" + payload.length + "B)");
        } catch (Throwable e) {
            Log.w(TAG, "vendor-override send: " + e);
        }
    }

    private static String buildPayload() throws Exception {
        JSONObject data = new JSONObject();
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba != null && ba.getName() != null) data.put("deviceBtName", ba.getName());
        } catch (Throwable ignored) {}
        if (!data.has("deviceBtName")) data.put("deviceBtName", "Galaxy S24");
        data.put("screenWidth", 1080);
        data.put("screenHeight", 2340);
        data.put("deviceName", "Galaxy S24");
        data.put("modelNumber", "SM-S921B");
        data.put("vender", "samsung");
        data.put("isTablet", false);
        data.put("csc", "");
        data.put("locale", java.util.Locale.getDefault().toString());
        data.put("timezone", java.util.TimeZone.getDefault().getID());
        data.put("time", System.currentTimeMillis());
        data.put("sepLiteDevice", false);

        JSONObject msg = new JSONObject();
        msg.put("msgId", "feature_exchange");
        msg.put("msgType", 2);
        msg.put("reason", 0);
        msg.put("result", 0);
        msg.put("data", data);
        return msg.toString();
    }
}
