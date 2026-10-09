package shminterop.relay;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/** Vendor identity override of the companion for the watch's wcs. The phone's heartplugin
 *  sends a feature_exchange with "vender": Build.BRAND over the GMS DataLayer
 *  (e.g. the phone manufacturer name); the watch's CompanionInfoProvider stores it and stock SHM
 *  rejects the companion. This periodically sends the same message with
 *  "vender": "samsung" over the same channel (path /wcs_extension/internal/feature),
 *  overwriting the value in the wcs preferences. */
public final class VendorIdentityOverride {

    private static final String TAG = "SHMRelay";
    private static final String PATH = "/wcs_extension/internal/feature";
    private static final long PERIOD_MS = 20000;
    private static volatile boolean sStarted;

    private VendorIdentityOverride() {}

    public static void start(Context ctx) {
        if (sStarted) return;
        synchronized (VendorIdentityOverride.class) {
            if (sStarted) return;
            sStarted = true;
            final Context app = ctx.getApplicationContext();
            Thread t = new Thread(new Runnable() {
                @Override public void run() { loop(app); }
            }, "vendor-override");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "VendorIdentityOverride iniciado");
        }
    }

    private static void loop(Context ctx) {
        while (true) {
            try {
                int ok = sendOnce(ctx);
                if (ok > 0) Log.i(TAG, "feature_exchange samsung enviado a " + ok + " nodo(s)");
            } catch (Throwable e) {
                Log.w(TAG, "vendor-override: " + e);
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static int sendOnce(Context ctx) throws Exception {
        // Bundled lib (psw 19.0.0) without NodeClient: the watch node is discovered
        // through the capability that the wcs advertises (wcs_extension_for_wearable).
        // NOTE: the runtime's Tasks.await(...) hangs indefinitely with
        // getCapability on some phones; use a callback + poll with our own timeout.
        CapabilityClient cc = Wearable.getCapabilityClient(ctx);
        final java.util.concurrent.SynchronousQueue<Object> q =
                new java.util.concurrent.SynchronousQueue<Object>();
        Task<CapabilityInfo> capTask = cc.getCapability(
                "wcs_extension_for_wearable", CapabilityClient.FILTER_ALL);
        capTask.addOnCompleteListener(new OnCompleteListener<CapabilityInfo>() {
            @Override public void onComplete(Task<CapabilityInfo> t) {
                try {
                    q.offer(t.isSuccessful() ? t.getResult() : t.getException());
                } catch (Throwable e) {
                    q.offer(e);
                }
            }
        });
        Object r = q.poll(10, java.util.concurrent.TimeUnit.SECONDS);
        if (r == null) {
            Log.w(TAG, "vendor-override: getCapability got no response in 10s");
            return 0;
        }
        CapabilityInfo info = (r instanceof CapabilityInfo) ? (CapabilityInfo) r : null;
        if (info == null) {
            Log.w(TAG, "vendor-override: getCapability fallo: " + r);
            return 0;
        }
        java.util.Collection<Node> nodes = info == null ? null : info.getNodes();
        if (nodes == null || nodes.isEmpty()) return 0;
        byte[] payload = buildPayload(ctx).getBytes("UTF-8");
        MessageClient mc = Wearable.getMessageClient(ctx);
        int sent = 0;
        Iterator<Node> it = nodes.iterator();
        while (it.hasNext()) {
            Node n = it.next();
            try {
                // Fire-and-forget: log the result via callback, without blocking.
                mc.sendMessage(n.getId(), PATH, payload)
                        .addOnCompleteListener(new OnCompleteListener<Integer>() {
                            @Override public void onComplete(Task<Integer> t) {
                                if (!t.isSuccessful()) {
                                    Log.w(TAG, "sendMessage fallo: " + t.getException());
                                }
                            }
                        });
                sent++;
            } catch (Throwable e) {
                Log.w(TAG, "send " + n.getId() + ": " + e);
            }
        }
        return sent;
    }

    private static String buildPayload(Context ctx) throws Exception {
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
