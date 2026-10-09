package shminterop.shmbridge.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * Phone notice for {@code /__bridge__/call/pulled}: a call placed from the watch
 * stayed on the phone because the audio did not move to the watch in time (typical: the phone asks
 * to pick a SIM on every call and the watch's HFP client gives up after 3 s). Tapping the notice
 * opens the SIM settings. Texts in res/values-* (scripts/gen-call-notice-strings.py).
 */
final class CallNotice {

    static final String CHANNEL = "call_notice";
    static final int ID = 0x5b20;

    private CallNotice() {}

    static void show(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    str(c, "call_notice_channel"), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent sim = new Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS");
        if (sim.resolveActivity(c.getPackageManager()) == null) sim = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        sim.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, ID, sim,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = str(c, "call_pulled_text");
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
                .setContentTitle(str(c, "call_pulled_title"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .build();
        nm.notify(ID, n);
        Log.i(CommsHub.TAG, "call/pulled: notice published (default SIM for calls: "
                + defaultSim(c) + ")");
    }

    /** "yes"/"no (always ask)"/"?" — for the log only, no account data. */
    private static String defaultSim(Context c) {
        try {
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            PhoneAccountHandle h = tm.getDefaultOutgoingPhoneAccount("tel");
            return h != null ? "yes" : "no (always ask)";
        } catch (Exception e) {
            return "?";
        }
    }

    /** Localized string by name (the build does not generate R). */
    static String str(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? name : c.getString(id);
    }
}
