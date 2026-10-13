package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Global state of the alarm ringer (local or mirrored from the phone): sound and vibration with
 * {@code USAGE_ALARM}, wake lock, and {@code category=alarm} notification with full-screen intent
 * to {@link RingActivity} (plus a direct startActivity if there is a BAL exemption). Everything runs on
 * the main thread.
 */
public final class AlarmRinger {

    static final String CHANNEL = "br_alarm";
    static final String TAG = "br:alarm";
    static final int ID = 0x5b1f;
    static final long MAX_RING_MS = 10 * 60 * 1000L;

    /** State read by RingActivity. */
    static volatile boolean ringing;
    static volatile boolean fromPhone;
    static volatile String phoneId;
    static volatile String label;
    static volatile boolean canSnooze = true;

    interface UiListener { void onRingStateChanged(); }
    private static volatile UiListener ui;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Ringtone ringtone;
    private static Vibrator vibrator;
    private static PowerManager.WakeLock wakeLock;
    private static final Runnable timeout = new Runnable() {
        @Override public void run() {
            Log.i(BridgeHub.TAG, "alarm: no response in " + (MAX_RING_MS / 60000) + " min, silencing");
            if (appCtx != null) stopNow(appCtx);
        }
    };
    private static Context appCtx;

    private AlarmRinger() {}

    static boolean isRinging() { return ringing; }

    static void setUi(UiListener l) { ui = l; }

    static void clearUi(UiListener l) { if (ui == l) ui = null; }

    static void start(Context c, final boolean phone, final String id, final String lbl, final boolean snoozable) {
        final Context ctx = c.getApplicationContext();
        main.post(new Runnable() {
            @Override public void run() { startNow(ctx, phone, id, lbl, snoozable); }
        });
    }

    private static void startNow(Context c, boolean phone, String id, String lbl, boolean snoozable) {
        appCtx = c;
        if (ringing) {
            if (phone) {
                // Adoption: the phone's ring takes over the ringer that is already sounding (local or previous).
                fromPhone = true;
                phoneId = id;
                if (lbl != null) label = lbl;
                canSnooze = snoozable;
                Log.i(BridgeHub.TAG, "alarm already ringing: adopts phone id=" + id);
                postNotification(c);
                notifyUi();
            }
            return;
        }
        ringing = true;
        fromPhone = phone;
        phoneId = phone ? id : null;
        label = lbl;
        canSnooze = snoozable;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shmbridge:alarm");
            wakeLock.acquire(MAX_RING_MS + 5000);
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "alarm wake lock: " + t);
        }
        playSound(c);
        vibrate(c);
        postNotification(c);
        launchActivity(c);
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, MAX_RING_MS);
        Log.i(BridgeHub.TAG, "alarm ringing (origin=" + (phone ? "phone id=" + id : "local") + ")");
        notifyUi();
    }

    /** User button (activity or notification action). */
    static void userAction(Context c, final boolean snooze) {
        final Context ctx = c.getApplicationContext();
        main.post(new Runnable() {
            @Override public void run() {
                if (!ringing) return;
                if (fromPhone && phoneId != null) {
                    BridgeHub.get(ctx).send(snooze ? "alarm/snooze" : "alarm/dismiss",
                            BridgeHub.obj("id", phoneId));
                } else if (snooze) {
                    AlarmMirror.snoozeLocal(ctx);
                }
                Log.i(BridgeHub.TAG, "alarm: " + (snooze ? "snooze" : "dismiss")
                        + " (origin=" + (fromPhone ? "phone" : "local") + ")");
                stopNow(ctx);
            }
        });
    }

    static void stopFromPhone(Context c, final String id) {
        final Context ctx = c.getApplicationContext();
        main.post(new Runnable() {
            @Override public void run() {
                if (!ringing || !fromPhone) return;
                if (id != null && !id.isEmpty() && phoneId != null && !phoneId.equals(id)) {
                    Log.i(BridgeHub.TAG, "alarm/stop for another id (" + id + "), ignored");
                    return;
                }
                stopNow(ctx);
            }
        });
    }

    private static void stopNow(Context c) {
        main.removeCallbacks(timeout);
        ringing = false;
        try { if (ringtone != null) ringtone.stop(); } catch (Throwable ignored) {}
        ringtone = null;
        try { if (vibrator != null) vibrator.cancel(); } catch (Throwable ignored) {}
        vibrator = null;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        wakeLock = null;
        BridgeNotifier.nm(c).cancel(TAG, ID);
        Log.i(BridgeHub.TAG, "alarm stopped");
        notifyUi();
    }

    private static void notifyUi() {
        UiListener l = ui;
        if (l != null) try { l.onRingStateChanged(); } catch (Throwable ignored) {}
    }

    private static void playSound(Context c) {
        try {
            Uri uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            Ringtone r = uri == null ? null : RingtoneManager.getRingtone(c, uri);
            if (r == null) {
                Log.w(BridgeHub.TAG, "alarm: no ringtone available, vibration only");
                return;
            }
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.setLooping(true);
            r.play();
            ringtone = r;
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "alarm: ringtone: " + t);
        }
    }

    @SuppressWarnings("deprecation")
    private static void vibrate(Context c) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect fx = VibrationEffect.createWaveform(new long[]{0, 700, 500}, 0);
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM));
            } else {
                v.vibrate(fx, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            }
            vibrator = v;
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "alarm: vibration: " + t);
        }
    }

    private static PendingIntent activityIntent(Context c) {
        Intent i = new Intent(c, RingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        return PendingIntent.getActivity(c, 3, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent alarmBroadcast(Context c, String action) {
        Intent i = new Intent(action).setClass(c, BridgeAlarmReceiver.class);
        return PendingIntent.getBroadcast(c, 4, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void postNotification(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 34 && !BridgeNotifier.nm(c).canUseFullScreenIntent()) {
                Log.w(BridgeHub.TAG, "alarm: no USE_FULL_SCREEN_INTENT (appop): heads-up only");
            }
            PendingIntent full = activityIntent(c);
            Notification.Builder b = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(label != null ? label : CallMirror.str(c, "alarm_title"))
                    .setContentText(CallMirror.str(c, fromPhone ? "alarm_from_phone" : "alarm_local"))
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setLocalOnly(true)
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(full)
                    .setFullScreenIntent(full, true);
            if (canSnooze) {
                b.addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, android.R.drawable.ic_popup_reminder), CallMirror.str(c, "alarm_snooze"),
                        alarmBroadcast(c, BridgeAlarmReceiver.ACTION_SNOOZE)).build());
            }
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(c, android.R.drawable.ic_menu_close_clear_cancel), CallMirror.str(c, "alarm_dismiss"),
                    alarmBroadcast(c, BridgeAlarmReceiver.ACTION_DISMISS)).build());
            Notification n = b.build();
            n.flags |= Notification.FLAG_INSISTENT;
            BridgeNotifier.nm(c).notify(TAG, ID, n);
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "alarm: notification: " + t);
        }
    }

    /** With the BAL exemption (appop SYSTEM_ALERT_WINDOW) the activity is opened even if the screen
     *  is on; without it the full-screen intent / heads-up remains. */
    private static void launchActivity(Context c) {
        try {
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    android.os.Process.myUid(), c.getPackageName());
            if (mode != android.app.AppOpsManager.MODE_ALLOWED) return;
            c.startActivity(new Intent(c, RingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "alarm: startActivity: " + t);
        }
    }
}
