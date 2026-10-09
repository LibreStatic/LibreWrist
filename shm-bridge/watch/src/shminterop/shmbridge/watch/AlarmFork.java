package shminterop.shmbridge.watch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Phone alarm in the fork of Samsung's Alarm app
 * ({@code shminterop.shmbridge.alarm}), through direct intents to {@code LinkActivity} and
 * {@code LinkReceiver}. The fork understands {@code /c_alarm/*} paths (companion -> fork) and
 * replies with {@code /w_alarm/*} broadcasts (fork -> companion).
 *
 * <p>Both directions are protected by the signature permission
 * {@code shminterop.shmbridge.permission.ALARM_LINK} (same keystore).</p>
 *
 * <p>Requirement (adb, once): BAL exemption ({@code appops set shminterop.shmbridge.watch
 * SYSTEM_ALERT_WINDOW allow}) to open {@code LinkActivity} from the background.</p>
 */
final class AlarmFork {

    static final String FORK_PKG = "shminterop.shmbridge.alarm";
    static final String PERM = "shminterop.shmbridge.permission.ALARM_LINK";
    static final String ACTION_IN = "shminterop.shmbridge.alarm.IN";
    static final String ACTION_OUT = "shminterop.shmbridge.alarm.OUT";
    static final ComponentName LINK_ACTIVITY =
            new ComponentName(FORK_PKG, "shminterop.shmbridge.alarm.LinkActivity");

    /** Id of the phone alarm associated with the fork's ring in progress. */
    static volatile String phoneId;
    /** Instant at which the ring was sent to the fork. */
    static volatile long ringAt;

    private AlarmFork() {}

    // ---- coverage -----------------------------------------------------------------------

    /**
     * For the own fallback alarm: has the fork already rung (or is it ringing) for {@code t}?
     * If the ring was sent to the fork at an instant close to {@code t}, the local alarm is
     * suppressed.
     */
    static boolean coveredByFork(long t) {
        long ra = ringAt;
        return ra > 0 && ra >= t - 60_000L;
    }

    // ---- enablement ---------------------------------------------------------------------

    /**
     * The integration is active: fork installed and BAL exemption
     * ({@code SYSTEM_ALERT_WINDOW}) to open {@code LinkActivity} from the background.
     */
    static boolean enabled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(FORK_PKG, 0);
        } catch (Exception e) {
            return false;
        }
        try {
            android.app.AppOpsManager ops = c.getSystemService(android.app.AppOpsManager.class);
            return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(), c.getPackageName())
                    == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- phone -> fork ------------------------------------------------------------------

    /**
     * Sends {@code /c_alarm/started} to the fork: the phone alarm started ringing.
     *
     * @param id    phone alarm id (string, hashed to int for alarmItem)
     * @param label alarm label (null -> "Alarma")
     * @param canSnooze whether the alarm can be snoozed
     * @param ts    fire instant (epoch ms)
     */
    static void ring(Context c, String id, String label, boolean canSnooze, long ts) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(ts);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int m = cal.get(Calendar.MINUTE);
            int alarmTime = h * 100 + m;

            JSONObject item = new JSONObject();
            item.put("id", id == null ? 0 : id.hashCode());
            item.put("alarmName", label != null ? label : "Alarma");
            item.put("alarmTime", alarmTime);
            item.put("bedAlarmTime", -1);
            item.put("active", 1);
            item.put("snz", canSnooze ? 1 : 0);
            item.put("alertTime", ts);
            item.put("snzDuration", 0);
            item.put("snzRepeat", 3);
            item.put("snzCount", 0);
            item.put("alarmSoundType", 0);
            item.put("dailyBr", 1);
            item.put("rep", 1);
            item.put("order", 0);
            item.put("currentTime", System.currentTimeMillis());
            item.put("group_id", 0);

            JSONObject wrapper = new JSONObject();
            wrapper.put("alarmItem", item.toString());
            wrapper.put("wearingState", true);

            Intent i = new Intent()
                    .setComponent(LINK_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("path", "/c_alarm/started")
                    .putExtra("data", wrapper.toString());
            c.startActivity(i);

            phoneId = id;
            ringAt = System.currentTimeMillis();
            Log.i(BridgeHub.TAG, "fork: ring id=" + id);
        } catch (Exception e) {
            Log.w(BridgeHub.TAG, "fork: ring fallido: " + e);
        }
    }

    /**
     * Sends {@code /c_alarm/stopped} to the fork: the phone alarm stopped ringing.
     */
    static void stop(Context c, String id) {
        try {
            Intent i = new Intent(ACTION_IN)
                    .setPackage(FORK_PKG)
                    .putExtra("path", "/c_alarm/stopped")
                    .putExtra("data", "");
            c.sendBroadcast(i, PERM);
            Log.i(BridgeHub.TAG, "fork: stop id=" + id);
        } catch (Exception e) {
            Log.w(BridgeHub.TAG, "fork: stop fallido: " + e);
        }
    }

    // ---- fork -> phone ------------------------------------------------------------------

    /**
     * Receives an action from the fork (broadcast {@link #ACTION_OUT}). Translates
     * {@code /w_alarm/dismiss} and {@code /w_alarm/snooze} into messages to the phone.
     */
    static void onForkAction(Context c, String path, String data) {
        String pid = phoneId;
        if (pid == null) {
            Log.i(BridgeHub.TAG, "fork: action " + path + " with no active ring, ignored");
            return;
        }
        if ("/w_alarm/dismiss".equals(path)) {
            BridgeHub.get(c).send("alarm/dismiss", BridgeHub.obj("id", pid));
            phoneId = null;
            Log.i(BridgeHub.TAG, "fork: dismiss -> telefono id=" + pid);
        } else if ("/w_alarm/snooze".equals(path)) {
            BridgeHub.get(c).send("alarm/snooze", BridgeHub.obj("id", pid));
            Log.i(BridgeHub.TAG, "fork: snooze -> telefono id=" + pid);
        } else {
            Log.i(BridgeHub.TAG, "fork: action desconocida path=" + path);
        }
    }
}
