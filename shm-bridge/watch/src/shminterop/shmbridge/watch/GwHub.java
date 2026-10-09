package shminterop.shmbridge.watch;

import android.app.AlarmManager;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Watch side of the GW channel ({@code /__gw__/}) — phase 2: Galaxy Wearable + heartplugin without GMS.
 * Pattern of {@link BridgeHub}: receives from {@link FrameMux} the frames with the prefix already
 * stripped (RFCOMM session) and replies on the same channel. Dispatcher of the flows that the
 * feasibility study marked as reachable in phase 2:
 *
 * <ul>
 *   <li><b>Battery</b> (P0): synthetic DataItems {@code wear://watch/c_devicecare/battery/
 *   base_info} and {@code wear://watch/w_module_sec_watch_setting/battery_changed} (paths
 *   verified in heartplugin's manifest: DcWearableListenerService and
 *   SettingsWearableListenerService; the settings filter requires host "watch"). They are published
 *   when the session opens, on receiving BatteryInfoProvider's requests and on every
 *   ACTION_BATTERY_CHANGED.</li>
 *   <li><b>Memory / RAM</b> (P2): DataItem {@code wear://watch/c_devicecare/memory/info}
 *   (keys mem_total/mem_available/memory_used) + message reply to
 *   {@code /c_ram/requestRamInfo} with {@code /c_ram/resultRamInfo} (best effort: the
 *   exact format of the stock message cannot be verified offline).</li>
 *   <li><b>Device state</b>: DataItem {@code wear://watch/w_module_sec_watch_setting/
 *   state} (own documented extension, no verified stock consumer) with Build.*,
 *   settings readable without permission, volumes, brightness and next wake-up.</li>
 *   <li><b>Mirror store</b> {@code /__d__/*} (put/del/all/all-r): see {@link GwData}.</li>
 * </ul>
 *
 * <p><b>Blocked, not improvised</b>: the real application of the watch's settings
 * (psm, dnd/policy, advanced, show_on_phone, wrist_detection) is done by gws
 * ({@code android.uid.system}, not fork-able) and there is no public API: those plugin messages
 * are logged and left in silence. Likewise any path without a handler: sendMessage does not require a
 * reply, so the reply is documented silence. SHM frames and
 * {@code /__bridge__/*} do not pass through here (the mux demultiplexes them).</p>
 *
 * <p>The DataItem payloads are a serialized DataMap ({@link GwDataMap}), the format
 * that heartplugin's {@code DataMap.fromByteArray} expects. Privacy: only paths/uris and
 * lengths go in the log, never bodies.</p>
 */
public final class GwHub {

    public static final String TAG = "SHMBridgeGw";
    static final String P = FrameMux.GW_PREFIX;
    /** Payload cap per received frame (real DataItems are small). */
    static final int MAX_ITEM = 512 * 1024;
    /** Minimum between battery publications per sticky change (anti-spam). */
    static final long BATTERY_PUSH_INTERVAL_MS = 5000;

    private static volatile GwHub instance;

    public static GwHub get(Context ctx) {
        GwHub h = instance;
        if (h == null) {
            synchronized (GwHub.class) {
                h = instance;
                if (h == null) {
                    h = new GwHub(ctx.getApplicationContext());
                    instance = h;
                }
            }
        }
        return h;
    }

    /** Synthetic uris: host "watch" (required by the Settings WLS filter of heartplugin)
     *  + paths verified in the manifest (Dc) or in the settings dump. */
    static final String URI_BATTERY_BASE = "wear://watch/c_devicecare/battery/base_info";
    static final String URI_BATTERY_CHANGED = "wear://watch/w_module_sec_watch_setting/battery_changed";
    static final String URI_MEMORY_INFO = "wear://watch/c_devicecare/memory/info";
    /** Own extension (no verified stock consumer): readable state of the watch. */
    static final String URI_STATE = "wear://watch/w_module_sec_watch_setting/state";
    static final String PATH_RAM_RESULT = "/c_ram/resultRamInfo";

    /** Battery request messages emitted by BatteryInfoProvider. */
    private static final String[] BATTERY_REQUESTS = {
            "c_battery/syncBatteryInfo", "c_battery/refreshBatteryInfo",
            "c_devicecare/battery/base_info", "c_devicecare/battery/sync",
            "c_devicecare/battery/refresh",
    };

    /** Prefixes of flows blocked in phase 2 (stock receivers gated by GMS or
     *  out of scope): brief log and silence. */
    private static final String[] BLOCKED_PREFIXES = {
            "w_module_sec_watch_setting/", "wcs_extension/", "w_callservice/", "w_cmc",
            "contacts", "c_alarm", "c_callservice", "samsung_health", "samsung_weather",
    };

    private final Context ctx;
    private final GwData.Store store = new GwData.Store();
    private final ExecutorService inbound = Executors.newSingleThreadExecutor(named("gw-hub-in"));
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(named("gw-hub-out"));
    private volatile FrameMux mux;
    private volatile long lastBatteryPush;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            long now = System.currentTimeMillis();
            if (now - lastBatteryPush < BATTERY_PUSH_INTERVAL_MS) return;
            lastBatteryPush = now;
            publishBatteryAsync(intent);
        }
    };

    private GwHub(Context ctx) {
        this.ctx = ctx;
    }

    public boolean isLinked() {
        FrameMux m = mux;
        return m != null && !m.isClosed();
    }

    // ------------------------------------------------------------------ mux events

    void onSessionOpen(FrameMux m) {
        mux = m;
        Log.i(TAG, "GW session open: publishing initial state");
        try {
            ctx.registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            Log.w(TAG, "battery receiver: " + t);
        }
        publishBatteryAsync(batterySticky());
        inbound.execute(new Runnable() {
            @Override public void run() {
                try {
                    publishState();
                } catch (Throwable t) {
                    Log.w(TAG, "estado inicial: " + t);
                }
            }
        });
    }

    void onSessionClosed(FrameMux m, String reason) {
        if (mux == m) mux = null;
        try {
            ctx.unregisterReceiver(batteryReceiver);
        } catch (Throwable ignored) {}
        Log.i(TAG, "GW session closed: " + reason);
    }

    void onGwFrame(FrameMux m, final String path, final byte[] data) {
        final byte[] payload = data == null ? new byte[0] : data;
        inbound.execute(new Runnable() {
            @Override public void run() {
                try {
                    dispatch(path, payload);
                } catch (Throwable t) {
                    Log.w(TAG, "despacho " + path + " len=" + payload.length + ": " + t);
                }
            }
        });
    }

    /** Debugging: injects a GW frame as if it came from the phone. */
    public void inject(String path, byte[] payload) {
        onGwFrame(null, path, payload);
    }

    // ------------------------------------------------------------------ dispatch

    private void dispatch(String path, byte[] data) {
        if (data.length > MAX_ITEM) {
            Log.w(TAG, "descartada " + path + ": len=" + data.length + " > " + MAX_ITEM);
            return;
        }
        String sub = path == null ? "" : path;
        if (sub.startsWith("/")) sub = sub.substring(1);
        String dataPath = GwData.dataPath(sub);
        if (dataPath != null) {
            dispatchData(dataPath, data);
            return;
        }
        for (String req : BATTERY_REQUESTS) {
            if (req.equals(sub)) {
                Log.i(TAG, "rx battery request: /" + sub);
                lastBatteryPush = System.currentTimeMillis();
                publishBatteryAsync(batterySticky());
                return;
            }
        }
        if ("c_ram/requestRamInfo".equals(sub)) {
            Log.i(TAG, "rx pedido RAM: /" + sub);
            publishMemory();
            sendAsync(PATH_RAM_RESULT, memoryMap().build());
            return;
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (sub.startsWith(prefix)) {
                Log.i(TAG, "rx unhandled (stock applier blocked): /" + sub
                        + " len=" + data.length);
                return;
            }
        }
        Log.i(TAG, "unhandled gw path: /" + sub);
    }

    /** Mirror DataClient: put/del update the store; all replies all-r with the snapshot.
     *  all-r does not arrive here (the phone requests the snapshot, fixed direction D2). */
    private void dispatchData(String dataPath, byte[] payload) {
        if (GwData.PATH_PUT.equals(dataPath)) {
            String uri = GwData.decodeUri(payload);
            if (uri == null) {
                Log.w(TAG, "d/put malformado (" + payload.length + " B)");
                return;
            }
            byte[] item = GwData.decodeDataAfterUri(payload);
            store.put(uri, item);
            Log.i(TAG, "d/put " + uri + " len=" + item.length);
        } else if (GwData.PATH_DEL.equals(dataPath)) {
            String uri = GwData.decodeUri(payload);
            if (uri == null) {
                Log.w(TAG, "malformed d/del (" + payload.length + " B)");
                return;
            }
            store.delete(uri);
            Log.i(TAG, "d/del " + uri);
        } else if (GwData.PATH_ALL.equals(dataPath)) {
            byte[] snap = GwData.encodeAllResponse(store.snapshot());
            Log.i(TAG, "d/all -> all-r n=" + store.size() + " len=" + snap.length);
            sendAsync("/" + GwData.PATH_ALL_R, snap);
        } else if (GwData.PATH_ALL_R.equals(dataPath)) {
            Log.i(TAG, "unexpected d/all-r on the watch (the phone requests the snapshot)");
        }
    }

    // ------------------------------------------------------------------ publications

    /** Publishes battery off the notifying thread (the sticky is read on the inbound thread). */
    private void publishBatteryAsync(final Intent battery) {
        inbound.execute(new Runnable() {
            @Override public void run() {
                try {
                    publishBattery(battery != null ? battery : batterySticky());
                } catch (Throwable t) {
                    Log.w(TAG, "battery: " + t);
                }
            }
        });
    }

    private void publishBattery(Intent battery) {
        if (battery == null) {
            Log.i(TAG, "battery: no ACTION_BATTERY_CHANGED sticky");
            return;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        GwDataMap dm = new GwDataMap()
                .putInt("battery_level", pct)
                .putInt("status", status)
                .putInt("plugged", plugged)
                .putInt("scale", scale);
        publishDataItem(URI_BATTERY_BASE, dm);
        publishDataItem(URI_BATTERY_CHANGED, dm);
    }

    private GwDataMap memoryMap() {
        long total = 0, avail = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            if (am != null) {
                am.getMemoryInfo(mi);
                total = mi.totalMem;
                avail = mi.availMem;
            }
        } catch (Throwable t) {
            Log.w(TAG, "memoria: " + t);
        }
        return new GwDataMap()
                .putLong("mem_total", total)
                .putLong("mem_available", avail)
                .putLong("memory_used", total > avail ? total - avail : 0);
    }

    private void publishMemory() {
        publishDataItem(URI_MEMORY_INFO, memoryMap());
    }

    /** State readable without permission: identity, verified settings, volumes,
     *  brightness and next wake-up. Own extension: there is no verified stock consumer of
     *  this DataItem; it remains as a diagnostic and phase 3 base. */
    private void publishState() {
        GwDataMap dm = new GwDataMap()
                .putString("model", Build.MODEL == null ? "" : Build.MODEL)
                .putString("manufacturer", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER)
                .putString("device", Build.DEVICE == null ? "" : Build.DEVICE)
                .putString("android_release", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE)
                .putInt("sdk_int", Build.VERSION.SDK_INT)
                .putString("wear_os_version_string", global("wear_os_version_string"))
                .putString("companion_plugin_version", global("companion_plugin_version"))
                .putBoolean("bluetooth_on", globalInt("bluetooth_on", 0) == 1)
                .putBoolean("airplane_mode_on", globalInt("airplane_mode_on", 0) == 1)
                .putInt("brightness", systemInt(Settings.System.SCREEN_BRIGHTNESS, -1))
                .putInt("volume_music", streamVolume(AudioManager.STREAM_MUSIC))
                .putInt("volume_ring", streamVolume(AudioManager.STREAM_RING))
                .putLong("next_alarm_ms", nextAlarm());
        Intent b = batterySticky();
        if (b != null) {
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            dm.putInt("battery_level", level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1);
        }
        publishDataItem(URI_STATE, dm);
    }

    private void publishDataItem(String uri, GwDataMap dm) {
        byte[] data = dm.build();
        store.put(uri, data);
        sendAsync("/" + GwData.PATH_PUT, GwData.encodeUriPayload(uri, data));
        Log.i(TAG, "tx d/put " + uri + " len=" + data.length);
    }

    // ------------------------------------------------------------------ Android reads

    private Intent batterySticky() {
        try {
            return ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Throwable t) {
            Log.w(TAG, "battery sticky: " + t);
            return null;
        }
    }

    private String global(String key) {
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private int globalInt(String key, int def) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private int systemInt(String key, int def) {
        try {
            return Settings.System.getInt(ctx.getContentResolver(), key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private int streamVolume(int stream) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            return am == null ? -1 : am.getStreamVolume(stream);
        } catch (Throwable t) {
            return -1;
        }
    }

    private long nextAlarm() {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo info = am == null ? null : am.getNextAlarmClock();
            return info == null ? 0L : info.getTriggerTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ send

    /** Queues the send through the mux. {@code path}: with leading slash, without the GW prefix. */
    private void sendAsync(final String path, final byte[] payload) {
        outbound.execute(new Runnable() {
            @Override public void run() {
                FrameMux m = mux;
                if (m == null || m.isClosed()) {
                    Log.i(TAG, "tx without link, dropped: " + path + " len=" + payload.length);
                    return;
                }
                try {
                    m.sendGw(path, payload);
                    Log.i(TAG, "tx " + P + path + " len=" + payload.length);
                } catch (Exception e) {
                    Log.w(TAG, "tx " + path + " failed: " + e);
                }
            }
        });
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
