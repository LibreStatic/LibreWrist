package shminterop.shmbridge.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelUuid;
import android.util.Log;

/**
 * Channel debugging (protected by {@code android.permission.DUMP}, i.e. shell only):
 * <pre>
 *   am broadcast --user 0 -n shminterop.shmbridge.phone/.CommsDebugReceiver \
 *       -a shminterop.shmbridge.DEBUG_SEND --es path /__bridge__/... --es json '{...}'
 * </pre>
 * With {@code --ez inject true} the frame is not sent to the watch: it is processed locally as
 * if it had arrived from the watch (phone-side test without touching the watch).
 * With {@code --es path status} it only logs the hub state.
 * With {@code --es path config [--es caps alarm,notif,call]} it stores the
 * channel configuration (see {@link CommsHub}).
 */
public class CommsDebugReceiver extends BroadcastReceiver {

    static final String ACTION = "shminterop.shmbridge.DEBUG_SEND";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        String path = intent.getStringExtra("path");
        String json = intent.getStringExtra("json");
        boolean inject = intent.getBooleanExtra("inject", false);
        CommsHub hub = CommsHub.get(ctx);
        if ("status".equals(path)) {
            Log.i(CommsHub.TAG, "debug: " + hub.status());
            return;
        }
        if ("config".equals(path)) {
            hub.configure(intent.getStringExtra("caps"));
            return;
        }
        if ("bt-sdp".equals(path) || "bt-uuids".equals(path)) {
            btWatch(ctx, "bt-sdp".equals(path), intent.getStringExtra("addr"));
            return;
        }
        if (path == null || !path.startsWith(BridgeLink.PREFIX)) {
            Log.w(CommsHub.TAG, "debug: path missing or outside " + BridgeLink.PREFIX);
            return;
        }
        hub.start();  // idempotent: in case the process was started by this broadcast
        hub.debug(path, json, inject);
    }

    /** HFP with the watch: if the bond was made over LE, the phone may end up without the list of
     *  the watch's classic services ("No profiles") and not offer Calls. {@code bt-sdp} requests
     *  a fresh SDP; {@code bt-uuids} only logs what is there. With {@code --es addr <MAC>} it uses that
     *  address (e.g. the watch's public BR/EDR one) instead of the bond's. Logs name and
     *  UUIDs, not the MAC. */
    private static void btWatch(Context ctx, boolean fetch, String addr) {
        try {
            BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) { Log.w(CommsHub.TAG, "debug bt: no adapter"); return; }
            java.util.Collection<BluetoothDevice> devs = addr != null
                    ? java.util.Collections.singleton(ad.getRemoteDevice(addr)) : ad.getBondedDevices();
            for (BluetoothDevice d : devs) {
                String n = d.getName();
                if (addr == null && (n == null || !n.contains("Watch"))) continue;
                ParcelUuid[] u = d.getUuids();
                Log.i(CommsHub.TAG, "debug bt: " + n + " tipo=" + d.getType()
                        + " uuids=" + (u == null ? "null" : java.util.Arrays.toString(u)));
                if (fetch) Log.i(CommsHub.TAG, "debug bt: fetchUuidsWithSdp=" + d.fetchUuidsWithSdp());
            }
        } catch (SecurityException e) {
            Log.w(CommsHub.TAG, "debug bt: " + e);
        }
    }
}
