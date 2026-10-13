package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.telecom.TelecomManager;
import android.os.Vibrator;
import android.util.Log;

/**
 * Audio-less call companion ({@code /__bridge__/call/state}): with {@code ringing}
 * it shows an incoming call full screen ({@link CallActivity} via full-screen intent,
 * {@code CallStyle} notification) that vibrates until the call is answered, rejected or
 * ends; Answer/Reject send {@code call/answer} / {@code call/reject}.
 * {@code offhook}/{@code idle} withdraw it.
 *
 * <p>With HFP connected to the phone (direct BT bond, watch = hands-free) the call also comes
 * in through the watch's Telecom and is handled by Samsung's dialer, with audio on the watch: in that
 * case this mirror shows nothing and does not vibrate. If it is still answered from here, it is handled
 * through local Telecom (HFP) so the phone moves the audio to the watch.</p>
 *
 * <p>The call notification of the phone's dialer (mirrored, {@code cat=call}) is not
 * published while ringing: only its title is taken, as the caller's name.</p>
 */
public final class CallMirror {

    /** v2: the v1 channel ({@code br_call}) was created without vibration and a channel's importance/vibration
     *  cannot be changed after creation. */
    static final String CHANNEL = "br_call_v2";
    static final String OLD_CHANNEL = "br_call";
    static final String TAG = "br:call";
    static final int ID = 0x5b1e;
    /** Notices (call handed over to the phone): no insistent vibration, normal importance. */
    static final String NOTICE_CHANNEL = "br_call_notice";
    static final int NOTICE_ID = 0x5b20;
    /** A call/pulled with no link is sent on the next hello if no more than this has passed. */
    static final long PULLED_TTL_MS = 120_000;
    private static volatile long pulledAt;
    /** Safety cutoff of the vibration if {@code idle} never arrives. */
    static final long MAX_RING_MS = 90_000;

    /** {@code BluetoothProfile.HEADSET_CLIENT} (hidden in the SDK). */
    private static final int PROFILE_HEADSET_CLIENT = 16;

    static volatile boolean ringing;
    /** Call in progress handled by Samsung's dialer over HFP (from ringing to idle). */
    static volatile boolean hfpCall;
    static volatile String callerName;
    private static Vibrator vibrator;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable TIMEOUT = new Runnable() {
        @Override public void run() { stopVibration(); }
    };
    private static volatile CallActivity ui;

    private CallMirror() {}

    static void ensureChannel(Context c) {
        NotificationManager nm = BridgeNotifier.nm(c);
        if (nm.getNotificationChannel(OLD_CHANNEL) != null) nm.deleteNotificationChannel(OLD_CHANNEL);
        String name = str(c, "call_channel");
        NotificationChannel old = nm.getNotificationChannel(CHANNEL);
        // Re-created only to refresh the localized name (the system keeps the user's settings).
        if (old != null && name.equals(String.valueOf(old.getName()))) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(str(c, "call_channel_desc"));
        // Continuous vibration is handled by startVibration(); the channel gives the first alert.
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 600, 400, 600});
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    static void onState(Context c, String s, String name) {
        Log.i(BridgeHub.TAG, "call/state s=" + s + " name=" + (name == null ? "no" : "yes"));
        if ("ringing".equals(s)) {
            if (hfpCall || hfpConnected(c)) {
                hfpCall = true;
                Log.i(BridgeHub.TAG, "call: HFP connected, the call is shown by Samsung's dialer");
                return;
            }
            if (name != null) callerName = name;
            showRinging(c);
        } else {
            if ("idle".equals(s)) hfpCall = false;
            cancel(c);
        }
    }

    /** Called by BridgeNotifier with the mirrored {@code cat=call} notification.
     *  @return true if it is absorbed (not published as a separate notification). */
    static boolean onCallNotification(Context c, String title) {
        if (hfpCall) return true; // Samsung's dialer already shows the call
        if (!ringing) return false;
        if (title != null && !title.isEmpty() && !title.equals(callerName)) {
            callerName = title;
            Log.i(BridgeHub.TAG, "call: name taken from the dialer notification");
            showRinging(c); // updates notification and screen
        }
        return true;
    }

    static void showRinging(Context c) {
        ensureChannel(c);
        boolean first = !ringing;
        ringing = true;
        String name = callerName != null ? callerName : "Llamada entrante";
        PendingIntent answer = BridgeNotifier.selfBroadcast(c, BridgeActionReceiver.ACTION_CALL_ANSWER,
                "call", 0, false);
        PendingIntent reject = BridgeNotifier.selfBroadcast(c, BridgeActionReceiver.ACTION_CALL_REJECT,
                "call", 1, false);
        Intent full = new Intent(c, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, 0x5b1f, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Person caller = new Person.Builder().setName(name).setImportant(true).build();
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(name)
                .setContentText(str(c, "call_on_phone"))
                .setCategory(Notification.CATEGORY_CALL)
                .setStyle(Notification.CallStyle.forIncomingCall(caller, reject, answer))
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .setOngoing(true)
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build();
        BridgeNotifier.nm(c).notify(TAG, ID, n);
        if (first) {
            startVibration(c);
            // Besides the full-screen intent (which with the screen on degrades to a
            // heads-up), the screen is opened directly: BAL exemption via SYSTEM_ALERT_WINDOW.
            try {
                c.startActivity(full);
            } catch (Throwable t) {
                Log.w(BridgeHub.TAG, "call: could not open the call screen: " + t);
            }
        }
        CallActivity a = ui;
        if (a != null) a.onCallStateChanged();
    }

    /** The watch's HFP (HEADSET_CLIENT) connected to some phone. */
    static boolean hfpConnected(Context c) {
        try {
            BluetoothManager bm = c.getSystemService(BluetoothManager.class);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            return ad != null && ad.getProfileConnectionState(PROFILE_HEADSET_CLIENT)
                    == BluetoothProfile.STATE_CONNECTED;
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "call: HFP state: " + t);
            return false;
        }
    }

    /** Answers/hangs up through the watch's Telecom if it has a call (comes in via HFP).
     *  @return true if resolved locally (the phone receives ATA/CHUP over HFP). */
    @SuppressWarnings("deprecation")
    static boolean actLocal(Context c, boolean answer) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            if (tm == null || !tm.isInCall()) return false; // isRinging() is not public API
            if (answer) tm.acceptRingingCall(); else tm.endCall();
            Log.i(BridgeHub.TAG, "call: " + (answer ? "answered" : "rejected") + " via local HFP");
            return true;
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "call: Telecom local: " + t);
            return false;
        }
    }

    /** Outgoing call from the watch that stayed on the phone (see {@link CallWatchService}): notifies here and
     *  asks the phone for the same notice ({@code call/pulled}). Texts in res/values-*. */
    static void onPulled(Context c) {
        NotificationManager nm = BridgeNotifier.nm(c);
        if (nm.getNotificationChannel(NOTICE_CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(NOTICE_CHANNEL,
                    str(c, "call_notice_channel"), NotificationManager.IMPORTANCE_DEFAULT));
        }
        String text = str(c, "call_pulled_text");
        Notification n = new Notification.Builder(c, NOTICE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
                .setContentTitle(str(c, "call_pulled_title"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .build();
        nm.notify(TAG, NOTICE_ID, n);
        pulledAt = System.currentTimeMillis();
        BridgeHub hub = BridgeHub.get(c);
        if (hub.isLinked()) flushPulled(hub);
        else Log.i(BridgeHub.TAG, "call/pulled: no link, pending for the next hello");
        Log.i(BridgeHub.TAG, "call: outgoing call handed to the phone (no audio in time), notice published");
    }

    /** Sends the pending {@code call/pulled} (if it is from the last {@link #PULLED_TTL_MS});
     *  called when publishing the notice and on every hello from the phone. */
    static void flushPulled(BridgeHub hub) {
        long at = pulledAt;
        pulledAt = 0;
        if (at == 0 || System.currentTimeMillis() - at > PULLED_TTL_MS) return;
        hub.send("call/pulled", "{\"cause\":\"no_audio\"}");
    }

    /** Localized string by name (the build does not generate R). */
    static String str(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? name : c.getString(id);
    }

    static String str(Context c, String name, Object... args) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? name : c.getString(id, args);
    }

    static void cancel(Context c) {
        boolean was = ringing;
        ringing = false;
        callerName = null;
        stopVibration();
        BridgeNotifier.nm(c).cancel(TAG, ID);
        CallActivity a = ui;
        if (a != null) a.onCallStateChanged();
        if (was) Log.i(BridgeHub.TAG, "call: call withdrawn from the watch");
    }

    static synchronized void startVibration(Context c) {
        stopVibration();
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            VibrationEffect fx = VibrationEffect.createWaveform(new long[]{0, 800, 600}, 0);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                v.vibrate(fx, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE));
            } else {
                v.vibrate(fx, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
            }
            vibrator = v;
            MAIN.postDelayed(TIMEOUT, MAX_RING_MS);
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "call: vibration: " + t);
        }
    }

    static synchronized void stopVibration() {
        MAIN.removeCallbacks(TIMEOUT);
        Vibrator v = vibrator;
        vibrator = null;
        if (v != null) try { v.cancel(); } catch (Throwable ignored) {}
    }

    static void setUi(CallActivity a) { ui = a; }

    static void clearUi(CallActivity a) { if (ui == a) ui = null; }
}
