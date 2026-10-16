package shminterop.shmbridge.watch;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.util.Log;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

/**
 * Phone alarm in the watch's Samsung Alarm app ({@code com.samsung.android.watch.alarm}),
 * through its public AlarmClock interface ({@code AlarmCTSHandleActivity}: {@code SET_ALARM} with
 * {@code SKIP_UI}, {@code SNOOZE_ALARM}, {@code DISMISS_ALARM}). It rings and is handled with the
 * official Samsung interface; the bridge only coordinates with the phone.
 *
 * <p>Requirements (adb, once): BAL exemption ({@code appops set shminterop.shmbridge.watch
 * SYSTEM_ALERT_WINDOW allow}) to open the activity from the background, and access to
 * notifications for {@link SamsungAlarmListener}
 * ({@code cmd notification allow_listener shminterop.shmbridge.watch/shminterop.shmbridge.watch.SamsungAlarmListener}).
 * Without either of them, {@link #enabled} is false and the own alarm ({@link AlarmRinger}) remains.</p>
 *
 * <ul>
 *   <li><b>Scheduling:</b> {@code SET_ALARM} without days is one-shot and rings at the next HH:MM, so
 *   it is sent when less than 24 h remain; if more, it is deferred with AlarmManager. Samsung's app
 *   deduplicates by time + name + repeat: the same HH:MM reuses (and reactivates) the row.
 *   Fixed localized label ({@code alarm_phone_label}).</li>
 *   <li><b>Limit:</b> the public interface does not allow deleting or disabling alarms. If the phone's
 *   alarm is deleted or moved, the old row remains; when it rings, if it was created by the
 *   bridge, the phone is not ringing it and the link is up, it dismisses itself
 *   ({@code DISMISS_ALARM}).</li>
 *   <li><b>Phone → watch:</b> {@code alarm/stop} with Samsung's ringing snoozes it if the
 *   phone announces a next alarm in under 31 min (snooze), otherwise dismisses it.</li>
 *   <li><b>Watch → phone:</b> when the user turns it off on the watch, the system's next
 *   alarm is checked: if it is Samsung's in under 31 min it was a snooze, otherwise a dismiss;
 *   {@code alarm/snooze} / {@code alarm/dismiss} is forwarded with the phone's ring id.</li>
 *   <li><b>Fallback:</b> the own alarm stays scheduled at {@code t + 5 s} and only rings if
 *   Samsung's did not; an {@code alarm/ring} without Samsung's ringing at 5 s also uses the own one.</li>
 *   <li><b>Watch-only copy:</b> Samsung's app may hold its own copy of a phone alarm (e.g. left over
 *   from a Samsung phone) that rings next to the fork. Its alert posts no visible notification,
 *   so {@link SamsungAlarmListener} misses it; {@link #dismissDuplicate} reads the usage events of
 *   its alert service instead (appop {@code GET_USAGE_STATS}).</li>
 * </ul>
 */
final class SamsungAlarm {

    static final String PKG = "com.samsung.android.watch.alarm";
    static final ComponentName CTS = new ComponentName(PKG, PKG + ".activity.AlarmCTSHandleActivity");
    static final String ACTION_SYNC = BridgeActionReceiver.PKG + ".ALARM_SAMSUNG_SYNC";

    static final long DAY_MS = 24 * 60 * 60 * 1000L;
    static final long MARGIN_MS = 60_000;
    static final long SNOOZE_WINDOW_MS = 31 * 60 * 1000L;
    static final long DECIDE_MS = 2500;
    static final long STALE_CHECK_MS = 6000;
    static final long SELF_CMD_MS = 5000;

    static final String K_SAMSUNG_T = "samsungT";      // t already scheduled in Samsung
    static final String K_MINUTES = "samsungMinutes";  // HH:MM created by the bridge
    static final String K_LAST_RANG = "lastRangT";     // t of the phone alarm that already rang
    static final String K_FAIL_T = "samsungFailT";     // t that Samsung did not accept (e.g. 20 alarms)
    static final String K_FAIL_AT = "samsungFailAt";
    static final long VERIFY_MS = 5000;
    static final long RETRY_MS = 60 * 60 * 1000L;

    private static final Handler main = new Handler(Looper.getMainLooper());

    /** State of Samsung's alarm (updated by {@link SamsungAlarmListener}). */
    private static volatile boolean firing;
    private static volatile long firingWhen;
    private static volatile long lastFireAt;
    private static volatile long snoozedAt;
    private static volatile long selfCmdAt;
    /** Last ring from the phone (to forward snooze/dismiss). */
    private static volatile String phoneId;
    private static volatile long phoneRingAt;
    private static volatile boolean deciding;

    private SamsungAlarm() {}

    /** The integration is active: Samsung app present, BAL exemption and notification access. */
    static boolean enabled(Context c) {
        try {
            if (c.getPackageManager().getActivityInfo(CTS, 0) == null) return false;
            android.app.AppOpsManager ops = c.getSystemService(android.app.AppOpsManager.class);
            if (ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(), c.getPackageName()) != android.app.AppOpsManager.MODE_ALLOWED) {
                return false;
            }
            return c.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                    new ComponentName(c, SamsungAlarmListener.class));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- scheduling --------------------------------------------------------------------

    /** Brings the phone's alarm {@code t} to Samsung's app (or defers until t − 24 h). */
    static synchronized void sync(Context c, long t) {
        if (!AlarmMirror.syncEnabled(c)) return;
        long now = System.currentTimeMillis();
        if (t <= now) return;
        if (t - now >= DAY_MS - MARGIN_MS) {
            long at = t - DAY_MS + MARGIN_MS;
            scheduleSync(c, at);
            Log.i(BridgeHub.TAG, "samsung: alarm more than 24 h away, scheduled in Samsung at " + at);
            return;
        }
        cancelSync(c);
        SharedPreferences p = AlarmMirror.prefs(c);
        final Context app = c.getApplicationContext();
        final long ft = t;
        Runnable check = new Runnable() {
            @Override public void run() { verify(app, ft); }
        };
        if (p.getLong(K_SAMSUNG_T, 0) == t) {
            Log.i(BridgeHub.TAG, "samsung: alarm already scheduled (t=" + t + ")");
            main.postDelayed(check, VERIFY_MS); // the row may have been deleted by hand
            return;
        }
        if (p.getLong(K_FAIL_T, 0) == t && now - p.getLong(K_FAIL_AT, 0) < RETRY_MS) {
            Log.i(BridgeHub.TAG, "samsung: did not accept this alarm less than 1 h ago; the own one remains");
            return;
        }
        if ((firing || deciding || now - snoozedAt < SNOOZE_WINDOW_MS) && t - now <= SNOOZE_WINDOW_MS) {
            Log.i(BridgeHub.TAG, "samsung: next alarm = snooze; covered by Samsung's snooze");
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        int h = cal.get(Calendar.HOUR_OF_DAY), m = cal.get(Calendar.MINUTE);
        Intent i = cts(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                .putExtra(AlarmClock.EXTRA_MESSAGE, CallMirror.str(c, "alarm_phone_label"));
        if (!start(c, i)) return;
        Set<String> mins = new HashSet<>(p.getStringSet(K_MINUTES, new HashSet<String>()));
        mins.add(hhmm(h, m));
        p.edit().putLong(K_SAMSUNG_T, t).putStringSet(K_MINUTES, mins).remove(K_FAIL_T).apply();
        Log.i(BridgeHub.TAG, "samsung: SET_ALARM " + hhmm(h, m) + " (t=" + t + ")");
        main.postDelayed(check, VERIFY_MS);
    }

    /**
     * Samsung's app returns no result (created, already exists, maximum of 20 alarms, error):
     * it is confirmed by looking at the system's next alarm. If the next one is Samsung's at {@code t},
     * it stuck; if there is none before {@code t} and it is not that one, it did not stick (the own one remains and it
     * is retried in 1 h); if there is another one before, it cannot be known.
     */
    private static synchronized void verify(Context c, long t) {
        SharedPreferences p = AlarmMirror.prefs(c);
        if (p.getLong(K_SAMSUNG_T, 0) != t) return;
        AlarmManager.AlarmClockInfo next = c.getSystemService(AlarmManager.class).getNextAlarmClock();
        long nt = next == null ? 0 : next.getTriggerTime();
        PendingIntent show = next == null ? null : next.getShowIntent();
        boolean samsung = show != null && PKG.equals(show.getCreatorPackage());
        if (samsung && Math.abs(nt - t) < 1000) {
            Log.i(BridgeHub.TAG, "samsung: alarm confirmed in Samsung's app");
        } else if (next != null && nt < t - 1000) {
            Log.i(BridgeHub.TAG, "samsung: there is another alarm before; cannot confirm");
        } else {
            p.edit().remove(K_SAMSUNG_T).putLong(K_FAIL_T, t)
                    .putLong(K_FAIL_AT, System.currentTimeMillis()).apply();
            Log.w(BridgeHub.TAG, "samsung: Samsung's app did not create the alarm (maximum of 20 alarms?);"
                    + " the own one rings");
        }
    }

    /** {@code alarm/next} replaced {@code oldT}: if it was ringing, it stays as "already rang". */
    static void onNextReplaced(Context c, long oldT) {
        long now = System.currentTimeMillis();
        if (oldT > 0 && oldT >= now - 10 * 60_000L && oldT <= now + 2 * 60_000L) {
            AlarmMirror.prefs(c).edit().putLong(K_LAST_RANG, oldT).apply();
        }
    }

    // ---- phone → watch ------------------------------------------------------------------

    /** {@code alarm/ring}: associates the id with Samsung's alarm; if it does not ring in 5 s, the own one. */
    static void onPhoneRing(final Context c, final String id, final String label, final boolean canSnooze) {
        phoneId = id;
        phoneRingAt = System.currentTimeMillis();
        if (firing) {
            Log.i(BridgeHub.TAG, "samsung: Samsung's rings; associated with the phone ring id=" + id);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (firing) {
                    Log.i(BridgeHub.TAG, "samsung: Samsung's rings; associated with ring id=" + id);
                } else if (System.currentTimeMillis() - snoozedAt < SNOOZE_WINDOW_MS) {
                    Log.i(BridgeHub.TAG, "samsung: Samsung's is snoozed; it will ring again by itself");
                } else if (id.equals(phoneId)) {
                    Log.i(BridgeHub.TAG, "samsung: Samsung's did not ring, the own one rings");
                    AlarmRinger.start(c, true, id, label, canSnooze);
                }
            }
        }, AlarmMirror.GRACE_MS);
    }

    /** {@code alarm/stop}: snoozes or dismisses Samsung's if it is ringing. */
    static void onPhoneStop(final Context c, String id) {
        if (id != null && !id.isEmpty() && phoneId != null && !phoneId.equals(id)) return;
        phoneId = null;
        if (!firing) return;
        deciding = true;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                deciding = false;
                if (!firing) return;
                long t = AlarmMirror.prefs(c).getLong(AlarmMirror.K_T, 0);
                long dt = t - System.currentTimeMillis();
                boolean snooze = dt > 0 && dt <= SNOOZE_WINDOW_MS;
                command(c, snooze);
                Log.i(BridgeHub.TAG, "samsung: the phone turned off the alarm → "
                        + (snooze ? "snooze" : "dismiss") + " in Samsung");
            }
        }, DECIDE_MS);
    }

    // ---- Samsung state (SamsungAlarmListener) ----------------------------------------------

    static void onFiring(final Context c, long when) {
        if (firing) return;
        firing = true;
        firingWhen = when;
        lastFireAt = System.currentTimeMillis();
        Log.i(BridgeHub.TAG, "samsung: Samsung alarm ringing (time=" + when + ")");
        main.postDelayed(new Runnable() {
            @Override public void run() { checkStale(c); }
        }, STALE_CHECK_MS);
    }

    static void onStopped(final Context c) {
        if (!firing) return;
        firing = false;
        if (System.currentTimeMillis() - selfCmdAt < SELF_CMD_MS) {
            Log.i(BridgeHub.TAG, "samsung: alarm turned off by the bridge");
            return;
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                boolean snooze = samsungSnoozePending(c);
                if (snooze) snoozedAt = System.currentTimeMillis();
                String id = phoneId;
                boolean phoneActive = id != null && System.currentTimeMillis() - phoneRingAt < 15 * 60_000L;
                Log.i(BridgeHub.TAG, "samsung: the user " + (snooze ? "snoozed" : "dismissed")
                        + " it on the watch" + (phoneActive ? " → phone id=" + id : ""));
                if (!phoneActive) return;
                if (!snooze) phoneId = null;
                BridgeHub.get(c).send(snooze ? "alarm/snooze" : "alarm/dismiss", BridgeHub.obj("id", id));
            }
        }, 1500);
    }

    static boolean isFiring() { return firing; }

    /** For the own fallback alarm: did Samsung's ring (or is it snoozed) for {@code t}? */
    static boolean coveredBySamsung(long t) {
        long now = System.currentTimeMillis();
        return firing || lastFireAt >= t - 60_000L || now - snoozedAt < SNOOZE_WINDOW_MS;
    }

    /** Old row created by the bridge that the phone no longer has: it is dismissed. */
    private static void checkStale(Context c) {
        if (!firing) return;
        long now = System.currentTimeMillis();
        if (phoneId != null && now - phoneRingAt < 2 * 60_000L) return;
        if (!BridgeHub.get(c).isLinked()) return; // without the phone we cannot know
        SharedPreferences p = AlarmMirror.prefs(c);
        long when = firingWhen > 0 ? firingWhen : lastFireAt;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(when);
        String key = hhmm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        if (!p.getStringSet(K_MINUTES, new HashSet<String>()).contains(key)) return;
        if (near(when, p.getLong(AlarmMirror.K_T, 0)) || near(when, p.getLong(K_LAST_RANG, 0))) return;
        Log.i(BridgeHub.TAG, "samsung: rings " + key + " from the bridge but the phone no longer has it → dismiss");
        command(c, false);
    }

    // ---- watch-only copy of the phone alarm --------------------------------------------------

    static final String ALERT_SERVICE = PKG + ".viewmodel.AlarmService";
    /** How far back the usage events are read. */
    static final long USAGE_LOOKBACK_MS = 30 * 60_000L;
    /** A Samsung ring that started up to this much before the phone's ring is taken as its copy. */
    static final long COPY_BEFORE_MS = 2 * 60_000L;
    /** Second look, in case the start event was not recorded yet. */
    static final long COPY_RECHECK_MS = 4000;

    /**
     * {@code alarm/stop}: if Samsung's app is ringing a copy of the phone alarm (it started around
     * the phone's ring at {@code ringAt}), it is dismissed (also when the phone snoozed: the fork
     * rings the snooze, a snoozed copy would ring twice again). {@code DISMISS_ALARM} with nothing
     * ringing opens Samsung's alarm list, so it is only sent when the alert service is running.
     */
    static void dismissDuplicate(final Context c, final long ringAt) {
        if (ringAt <= 0) return;
        if (!hasUsageAccess(c)) {
            Log.i(BridgeHub.TAG, "samsung: no usage access (appops GET_USAGE_STATS), copies not checked");
            return;
        }
        if (dismissCopy(c, ringAt)) return;
        main.postDelayed(new Runnable() {
            @Override public void run() { dismissCopy(c, ringAt); }
        }, COPY_RECHECK_MS);
    }

    /** true if Samsung's alert service is running (whether or not it was dismissed). */
    private static boolean dismissCopy(Context c, long ringAt) {
        long started = alertStartedAt(c);
        if (started < 0) return false;
        if (started < ringAt - COPY_BEFORE_MS) {
            Log.i(BridgeHub.TAG, "samsung: Samsung's alarm rings since before the phone's, left alone");
            return true;
        }
        Log.i(BridgeHub.TAG, "samsung: Samsung's alarm rings a copy of the phone's → dismiss");
        command(c, false);
        return true;
    }

    /** Start time of Samsung's alert service if it is running now, otherwise -1. */
    static long alertStartedAt(Context c) {
        try {
            UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - USAGE_LOOKBACK_MS, now + 1000);
            UsageEvents.Event e = new UsageEvents.Event();
            long started = -1;
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (!PKG.equals(e.getPackageName()) || !ALERT_SERVICE.equals(e.getClassName())) continue;
                if (e.getEventType() == UsageEvents.Event.FOREGROUND_SERVICE_START) started = e.getTimeStamp();
                else if (e.getEventType() == UsageEvents.Event.FOREGROUND_SERVICE_STOP) started = -1;
            }
            return started;
        } catch (Exception ex) {
            Log.w(BridgeHub.TAG, "samsung: usage events: " + ex);
            return -1;
        }
    }

    static boolean hasUsageAccess(Context c) {
        try {
            android.app.AppOpsManager ops = c.getSystemService(android.app.AppOpsManager.class);
            return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName()) == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- utilities -------------------------------------------------------------------------

    private static void command(Context c, boolean snooze) {
        selfCmdAt = System.currentTimeMillis();
        if (snooze) snoozedAt = selfCmdAt;
        start(c, cts(snooze ? AlarmClock.ACTION_SNOOZE_ALARM : AlarmClock.ACTION_DISMISS_ALARM));
    }

    /** Is the system's next alarm Samsung's and in under 31 min? (= it was snoozed). */
    private static boolean samsungSnoozePending(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = am.getNextAlarmClock();
        if (next == null) return false;
        long dt = next.getTriggerTime() - System.currentTimeMillis();
        PendingIntent show = next.getShowIntent();
        String creator = show == null ? null : show.getCreatorPackage();
        return dt > 0 && dt <= SNOOZE_WINDOW_MS && PKG.equals(creator);
    }

    private static boolean near(long a, long b) {
        return b > 0 && Math.abs(a - b) <= 60_000L;
    }

    private static String hhmm(int h, int m) {
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private static Intent cts(String action) {
        return new Intent(action).setComponent(CTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION
                        | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }

    private static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(BridgeHub.TAG, "samsung: " + i.getAction() + " rejected: " + e);
            return false;
        }
    }

    private static PendingIntent syncIntent(Context c) {
        Intent i = new Intent(ACTION_SYNC).setClass(c, BridgeAlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 5, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleSync(Context c, long at) {
        try {
            c.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, at, syncIntent(c));
        } catch (SecurityException e) {
            Log.w(BridgeHub.TAG, "samsung: could not defer: " + e);
        }
    }

    static void cancelSync(Context c) {
        c.getSystemService(AlarmManager.class).cancel(syncIntent(c));
    }
}
