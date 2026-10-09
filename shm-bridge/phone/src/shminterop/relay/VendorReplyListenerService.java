package shminterop.relay;

import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

/** Phone-side Wearable listener: GMS delivers ALL of the device's wearable traffic to it
 *  (including wcs messages). On seeing traffic from a node
 *  (the watch), it replies with a feature_exchange with vendor samsung
 *  ("vender": "samsung") so the watch's CompanionInfoProvider serves
 *  vendor=samsung and stock SHM passes its companion gate. */
public class VendorReplyListenerService extends WearableListenerService {

    private static final String TAG = "SHMRelay";
    private static final String PATH = "/wcs_extension/internal/feature";
    private static volatile String sLastNodeId;
    private static volatile long sLastSend;
    private static volatile VendorReplyListenerService sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "VendorReplyListenerService onCreate");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onMessageReceived(MessageEvent event) {
        maybeSendVendorReply(event.getSourceNodeId());
    }

    @Override
    public void onPeerConnected(Node node) {
        maybeSendVendorReply(node.getId());
    }

    private static void maybeSendVendorReply(String nodeId) {
        if (nodeId == null) return;
        VendorReplyListenerService self = sInstance;
        if (self == null) return;
        long now = System.currentTimeMillis();
        String last = sLastNodeId;
        if (nodeId.equals(last) && now - sLastSend < 60000) return;
        sLastNodeId = nodeId;
        sLastSend = now;
        try {
            byte[] payload = buildPayload().getBytes("UTF-8");
            Wearable.getMessageClient(self).sendMessage(nodeId, PATH, payload);
            Log.i(TAG, "feature_exchange samsung enviado a " + nodeId);
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
