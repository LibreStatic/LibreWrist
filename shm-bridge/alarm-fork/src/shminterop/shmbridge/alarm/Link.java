package shminterop.shmbridge.alarm;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;

/**
 * Transport of the Samsung Alarm app fork: replaces the Wear (GMS) data layer with
 * intents to the watch bridge app ({@code shminterop.shmbridge.watch}).
 *
 * <p>Input: the same {@code /c_alarm/*} messages the Samsung phone sends to the watch
 * ({@code started}, {@code stopped}, {@code muted}, {@code snoozeChanged}), with the same
 * JSON body. They are handled like {@code AlarmSyncService.onMessageReceived} in the original app.
 * Output: what the app sends to the phone ({@code /w_alarm/dismiss}, {@code /w_alarm/snooze},
 * ...) goes out as a broadcast to the bridge.</p>
 *
 * <p>The app's internal classes are obfuscated; the names below correspond to
 * AlarmWatch 1.3.05.9 and the build script verifies that they still exist.</p>
 */
public final class Link {

    static final String TAG = "SHMBridgeAlarm";
    public static final String PERM = "shminterop.shmbridge.permission.ALARM_LINK";
    static final String BRIDGE_PKG = "shminterop.shmbridge.watch";
    static final String ACTION_OUT = "shminterop.shmbridge.alarm.OUT";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_DATA = "data";

    // AlarmWatch 1.3.05.9 (obfuscated)
    private static final String JSON_MODEL = "o2.b";          // a(String) -> o2.c (alarmItem)
    private static final String LOCAL_BCAST = "k0.b";         // a(Context) -> k0.b; c(Intent)
    private static final String SYNC_SERVICE = "com.samsung.android.watch.alarm.service.AlarmSyncService"; // m: start of the last "started"
    private static final String C_ALARM_SERVICE = "com.samsung.android.watch.alarm.viewmodel.CAlarmService";
    private static final String PFX = "com.samsung.android.watch.alarm.";

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Runnable sKillRunnable;

    private Link() {}

    /** Dispatches an incoming message. {@code done} is called when finished (for goAsync). */
    static void dispatch(Context c, String path, String data, Runnable done) {
        Log.i(TAG, "in " + path);
        try {
            switch (path) {
                case "/c_alarm/started":
                    cancelPendingKill();
                    started(c, data);
                    break;
                case "/c_alarm/stopped": {
                    long delay = getLastStarted() + 2000 - System.currentTimeMillis();
                    Runnable r = () -> {
                        try { local(c, new Intent(PFX + "C_ACTION_ALARM_COMMON_STOP_ALERT_AND_SERVICE")); }
                        catch (Exception e) { Log.e(TAG, "stop local", e); }
                        try { local(c, new Intent(PFX + "C_ACTION_LOCAL_ALARM_STOP_ALERT_SERVICE")); }
                        catch (Exception e) { Log.e(TAG, "stop alert svc", e); }
                        killAlarm(c);
                        done.run();
                    };
                    if (delay > 0) {
                        new Handler(Looper.getMainLooper()).postDelayed(r, delay);
                        return;
                    }
                    r.run();
                    return;
                }
                case "/c_alarm/muted":
                    local(c, new Intent(PFX + "C_ACTION_LOCAL_ALARM_MUTE"));
                    break;
                case "/c_alarm/snoozeChanged":
                    local(c, new Intent(PFX + "C_ACTION_LOCAL_ALARM_SNOOZE_CHANGED")
                            .putExtra("dynamicSnoozeDuration", data));
                    break;
                default:
                    Log.w(TAG, "unknown path " + path);
            }
        } catch (Exception e) {
            Log.e(TAG, "dispatch " + path, e);
        }
        done.run();
    }

    private static void started(Context c, String data) throws Exception {
        JSONObject o = new JSONObject(data);
        String item = o.getString("alarmItem");
        boolean wearing = o.optBoolean("wearingState", true);
        setLastStarted(System.currentTimeMillis());
        Object model = Class.forName(JSON_MODEL).getMethod("a", String.class).invoke(null, item);
        byte[] bytes = (byte[]) model.getClass().getMethod("f").invoke(model);
        Intent i = new Intent().setClassName(c, C_ALARM_SERVICE)
                .putExtra(PFX + "ALARM_DATA", bytes)
                .putExtra(PFX + "WEARING_STATE", wearing);
        c.startForegroundService(i);
        Log.i(TAG, "CAlarmService started");
    }

    private static void local(Context c, Intent i) throws Exception {
        Class<?> k = Class.forName(LOCAL_BCAST);
        Object lbm = k.getMethod("a", Context.class).invoke(null, c);
        k.getMethod("c", Intent.class).invoke(lbm, i);
    }

    private static long getLastStarted() {
        try {
            return syncField().getLong(null);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void setLastStarted(long t) {
        try {
            syncField().setLong(null, t);
        } catch (Exception ignored) {}
    }

    private static Field syncField() throws Exception {
        Field f = Class.forName(SYNC_SERVICE).getField("m");
        f.setAccessible(true);
        return f;
    }

    private static void cancelPendingKill() {
        if (sKillRunnable != null) {
            sHandler.removeCallbacks(sKillRunnable);
            sKillRunnable = null;
        }
    }

    private static void killAlarm(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.cancel();
        } catch (Exception ignored) {}
        try {
            c.stopService(new Intent().setClassName(c, C_ALARM_SERVICE));
        } catch (Exception ignored) {}
        Log.i(TAG, "killAlarm: vibrator cancelled, service stopped");
        cancelPendingKill();
        sKillRunnable = () -> {
            Log.w(TAG, "killAlarm: final safety net - killing process");
            android.os.Process.killProcess(android.os.Process.myPid());
        };
        sHandler.postDelayed(sKillRunnable, 2000);
    }

    /**
     * Replacement for {@code o2.b.e(Context, path, data, callback)} (the app's sendMessageToAll):
     * instead of sending it to the Wear nodes, it hands it to the bridge.
     */
    public static void send(Context c, String path, String data) {
        Log.i(TAG, "out " + path + " " + data);
        Intent i = new Intent(ACTION_OUT).setPackage(BRIDGE_PKG)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_DATA, data);
        c.sendBroadcast(i, PERM);
    }
}
