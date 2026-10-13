package shminterop.shmbridge.watch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Mirror of the phone's next alarm ({@code /__bridge__/alarm/*}).
 *
 * <p>A single alarm managed with {@link AlarmManager#setAlarmClock}, replaced on every
 * {@code alarm/next} ({@code t=null} cancels it). It rings even if the link is down. The
 * instant is kept in SharedPreferences and re-armed on BOOT_COMPLETED / MY_PACKAGE_REPLACED.</p>
 *
 * <p>Anti-duplicate (contract): the local alarm fires at {@code t + 5 s}; if by
 * then {@code alarm/ring} arrived from the phone (or it is already ringing), the local one is suppressed.
 * If the {@code ring} arrives late with the local one ringing, the local ring adopts the
 * phone's id (Snooze/Dismiss then go to the phone).</p>
 *
 * <p>With {@link AlarmFork#enabled} the alarm is handed to the fork of Samsung's Alarm app
 * (native UI, fed by the bridge). If the fork is not available,
 * {@link AlarmRinger} rings as a fallback.</p>
 */
public final class AlarmMirror {

    static final String PREFS = "bridge_alarm";
    static final String K_T = "t";            // alarm instant (epoch ms)
    static final String K_FIRE = "fire";      // scheduled fire = t + GRACE
    static final String K_RING_AT = "ringAt"; // last alarm/ring received (epoch ms)
    static final long GRACE_MS = 5000;
    static final long SNOOZE_MS = 10 * 60 * 1000L;
    /** A phone ring that arrived up to this much before t counts as "this alarm's". */
    static final long RING_MATCH_BEFORE_MS = 60000;

    private AlarmMirror() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** {@code alarm/next}: {@code t == null} cancels. */
    static synchronized void setNext(Context c, Long t) {
        if (t == null || t <= 0) {
            cancel(c);
            prefs(c).edit().remove(K_T).remove(K_FIRE).apply();
            Log.i(BridgeHub.TAG, "alarm/next: no alarm (cancelled)");
            return;
        }
        Log.i(BridgeHub.TAG, "alarm/next: t=" + t + " (in " + ((t - System.currentTimeMillis()) / 1000) + " s)");
        schedule(c, t);
    }

    /** Schedules the managed alarm to ring at {@code t + GRACE}. */
    static synchronized void schedule(Context c, long t) {
        long fire = t + GRACE_MS;
        if (fire <= System.currentTimeMillis()) {
            cancel(c);
            prefs(c).edit().remove(K_T).remove(K_FIRE).apply();
            Log.i(BridgeHub.TAG, "alarm in the past: not scheduled");
            return;
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (!am.canScheduleExactAlarms()) {
            Log.w(BridgeHub.TAG, "no exact-alarm permission (USE_EXACT_ALARM / appop SCHEDULE_EXACT_ALARM)");
        }
        prefs(c).edit().putLong(K_T, t).putLong(K_FIRE, fire).apply();
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(fire, showIntent(c)), fireIntent(c));
            Log.i(BridgeHub.TAG, "local alarm scheduled: fire=" + fire);
        } catch (SecurityException e) {
            Log.w(BridgeHub.TAG, "setAlarmClock rechazado: " + e);
        }
    }

    static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(fireIntent(c));
    }

    /** BOOT_COMPLETED / MY_PACKAGE_REPLACED / exact-alarm permission change. */
    static synchronized void rearm(Context c) {
        long t = prefs(c).getLong(K_T, 0);
        if (t <= 0) {
            Log.i(BridgeHub.TAG, "re-arm: no saved alarm");
            return;
        }
        Log.i(BridgeHub.TAG, "re-arm of the saved alarm t=" + t);
        schedule(c, t);
    }

    /** Fire of the local alarm (t + GRACE). */
    static synchronized void onFire(Context c) {
        SharedPreferences p = prefs(c);
        long t = p.getLong(K_T, 0);
        long ringAt = p.getLong(K_RING_AT, 0);
        p.edit().remove(K_T).remove(K_FIRE).apply();
        if (AlarmRinger.isRinging()) {
            Log.i(BridgeHub.TAG, "local alarm suppressed: already ringing");
            return;
        }
        if (t > 0 && AlarmFork.coveredByFork(t)) {
            Log.i(BridgeHub.TAG, "local alarm suppressed: the fork rang");
            return;
        }
        if (t > 0 && ringAt >= t - RING_MATCH_BEFORE_MS) {
            Log.i(BridgeHub.TAG, "local alarm suppressed: the phone already notified (ring)");
            return;
        }
        Log.i(BridgeHub.TAG, "local alarm: no ring from the phone in " + GRACE_MS + " ms, the local one rings");
        if (AlarmFork.enabled(c)) {
            AlarmFork.ring(c, null, null, true, t > 0 ? t : System.currentTimeMillis());
        } else {
            AlarmRinger.start(c, false, null, null, true);
        }
    }

    /** {@code alarm/ring} from the phone. */
    static void onPhoneRing(Context c, String id, String label, boolean canSnooze) {
        long now = System.currentTimeMillis();
        long ts = prefs(c).getLong(K_T, now);
        prefs(c).edit().putLong(K_RING_AT, now).apply();
        Log.i(BridgeHub.TAG, "alarm/ring id=" + id + " canSnooze=" + canSnooze);
        if (AlarmFork.enabled(c)) {
            AlarmFork.ring(c, id, label, canSnooze, ts);
            return;
        }
        AlarmRinger.start(c, true, id, label, canSnooze);
    }

    /** {@code alarm/stop} from the phone. */
    static void onPhoneStop(Context c, String id) {
        Log.i(BridgeHub.TAG, "alarm/stop id=" + id);
        AlarmFork.stop(c, id);
        AlarmRinger.stopFromPhone(c, id);
    }

    /** Snooze an alarm that rang only locally (no ring from the phone). */
    static void snoozeLocal(Context c) {
        long t = System.currentTimeMillis() + SNOOZE_MS - GRACE_MS;
        Log.i(BridgeHub.TAG, "local alarm snoozed " + (SNOOZE_MS / 60000) + " min");
        schedule(c, t);
    }

    private static PendingIntent fireIntent(Context c) {
        Intent i = new Intent(BridgeAlarmReceiver.ACTION_FIRE).setClass(c, BridgeAlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent showIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
