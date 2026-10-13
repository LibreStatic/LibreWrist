package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Local mirror of the phone's notifications ({@code /__bridge__/notif/*}).
 *
 * <p>Each notification is published with tag {@code br:<id>} and a fixed integer id, in a channel
 * per source package ({@code br_<pkg>}, name = app name, so it can be muted
 * per app on the watch). Since a channel's importance is fixed once created, those of
 * category msg/call/alarm go to a sibling channel {@code br_<pkg>_hi} (HIGH); both within
 * the app's channel group. Actions, dismissal and tap return to the phone
 * via {@link BridgeActionReceiver} with explicit PendingIntents.</p>
 */
public final class BridgeNotifier {
    /** Notifications older than this are published without sound or vibration. */
    static final long BACKLOG_SILENT_MS = 60_000;


    static final String TAG_PREFIX = "br:";
    static final int NOTIF_ID = 0x5b1d;
    static final String REPLY_KEY = "br_reply";
    static final String SCHEME = "shmbridge";

    /** ids cancelled on the phone's order: if the system fires the deleteIntent anyway,
     *  no {@code notif/dismiss} is sent back (loop closure). */
    private static final Map<String, Long> removedByPhone =
            Collections.synchronizedMap(new HashMap<String, Long>());

    private BridgeNotifier() {}

    static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void ensureFixedChannels(Context c) {
        NotificationManager nm = nm(c);
        CallMirror.ensureChannel(c);
        NotificationChannel alarm = new NotificationChannel(AlarmRinger.CHANNEL,
                CallMirror.str(c, "alarm_channel"), NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription(CallMirror.str(c, "alarm_channel_desc"));
        alarm.setSound(null, null);          // sound is handled by AlarmRinger (USAGE_ALARM)
        alarm.enableVibration(false);
        alarm.setBypassDnd(true);
        nm.createNotificationChannel(alarm);
    }

    private static String channelFor(Context c, String pkg, String app, boolean high) {
        String safePkg = pkg == null || pkg.isEmpty() ? "unknown" : pkg;
        String label = app == null || app.isEmpty() ? safePkg : app;
        String groupId = "brg_" + safePkg;
        String id = "br_" + safePkg + (high ? "_hi" : "");
        NotificationManager nm = nm(c);
        String name = high ? CallMirror.str(c, "notif_channel_urgent", label) : label;
        NotificationChannel old = nm.getNotificationChannel(id);
        // Renamed (same id, the system keeps the user's settings) when the name changes, e.g. after
        // a locale change; only with a real app label, so the package-name fallback cannot flap it.
        boolean rename = old != null && app != null && !app.isEmpty()
                && !name.equals(String.valueOf(old.getName()));
        if (old == null || rename) {
            nm.createNotificationChannelGroup(new NotificationChannelGroup(groupId, label));
            NotificationChannel ch = new NotificationChannel(id, name,
                    high ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
            ch.setGroup(groupId);
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    static boolean isHighCat(String cat) {
        return Notification.CATEGORY_MESSAGE.equals(cat) || Notification.CATEGORY_CALL.equals(cat)
                || Notification.CATEGORY_ALARM.equals(cat);
    }

    // ------------------------------------------------------------------ phone → watch

    static void post(Context c, JSONObject j) {
        String id = j.optString("id", "");
        String pkg = j.optString("pkg", "");
        if (id.isEmpty()) {
            Log.w(BridgeHub.TAG, "notif/post without id (pkg=" + pkg + ")");
            return;
        }
        removedByPhone.remove(id);
        String cat = BridgeHub.str(j, "cat");
        if (Notification.CATEGORY_CALL.equals(cat)
                && CallMirror.onCallNotification(c, BridgeHub.str(j, "title"))) {
            // The incoming call is already shown by CallMirror in full screen.
            Log.i(BridgeHub.TAG, "notif id=" + id + " (incoming call) absorbed by CallMirror");
            return;
        }
        boolean high = isHighCat(cat);
        String channel = channelFor(c, pkg, BridgeHub.str(j, "app"), high);

        String title = BridgeHub.str(j, "title");
        String text = BridgeHub.str(j, "text");
        String big = BridgeHub.str(j, "big");
        long ts = j.optLong("ts", System.currentTimeMillis());

        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(smallIconFor(cat))
                .setContentTitle(title)
                .setContentText(text)
                .setWhen(ts)
                .setShowWhen(true)
                .setLocalOnly(true)
                .setAutoCancel(true)
                // Updates of the same id do not vibrate again, and stale items (the
                // full resend after each hello) come in silently: only what is new alerts.
                .setOnlyAlertOnce(true)
                .setOngoing(!j.optBoolean("dismissible", true))
                .setGroup("brg:" + pkg + (BridgeHub.str(j, "group") == null ? "" : ":" + BridgeHub.str(j, "group")))
                .setContentIntent(selfBroadcast(c, BridgeActionReceiver.ACTION_NOTIF_OPEN, id, -1, false))
                .setDeleteIntent(selfBroadcast(c, BridgeActionReceiver.ACTION_NOTIF_DISMISS, id, -1, false));
        if (System.currentTimeMillis() - ts > BACKLOG_SILENT_MS) {
            // Equivalent of NotificationCompat.setSilent: a group member with "summary only"
            // alert neither rings nor vibrates (we publish no summary of our own).
            b.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        }
        if (cat != null) b.setCategory(cat);
        String sub = BridgeHub.str(j, "sub");
        if (sub != null) b.setSubText(sub);
        android.os.Bundle extras = new android.os.Bundle();
        extras.putString("br_id", id);
        extras.putString("br_pkg", pkg);
        b.addExtras(extras);

        Icon large = decodeIcon(BridgeHub.str(j, "icon"));
        if (large != null) b.setLargeIcon(large);

        JSONArray msgs = j.optJSONArray("msgs");
        if (j.optBoolean("conv", false) && msgs != null && msgs.length() > 0) {
            Person me = new Person.Builder().setName("Yo").build();
            Notification.MessagingStyle ms = new Notification.MessagingStyle(me);
            java.util.Set<String> senders = new java.util.HashSet<>();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String from = BridgeHub.str(m, "from");
                Person p = from == null ? null : new Person.Builder().setName(from).build();
                if (from != null) senders.add(from);
                ms.addMessage(new Notification.MessagingStyle.Message(
                        nz(BridgeHub.str(m, "text")), m.optLong("ts", ts), p));
            }
            if (senders.size() > 1) {
                ms.setGroupConversation(true);
                ms.setConversationTitle(title);
            }
            b.setStyle(ms);
        } else if (big != null) {
            b.setStyle(new Notification.BigTextStyle().bigText(big));
        }

        JSONArray actions = j.optJSONArray("actions");
        int nActions = 0;
        if (actions != null) {
            for (int k = 0; k < actions.length(); k++) {
                JSONObject a = actions.optJSONObject(k);
                if (a == null) continue;
                int idx = a.optInt("i", k);
                String label = nz(BridgeHub.str(a, "label"));
                boolean reply = a.optBoolean("reply", false);
                PendingIntent pi = selfBroadcast(c, BridgeActionReceiver.ACTION_NOTIF_ACTION, id, idx, true);
                Notification.Action.Builder ab = new Notification.Action.Builder(
                        Icon.createWithResource(c, reply ? android.R.drawable.ic_menu_send
                                : android.R.drawable.ic_menu_more), label, pi);
                if (reply) {
                    RemoteInput.Builder rb = new RemoteInput.Builder(REPLY_KEY).setLabel(label);
                    JSONArray ch = a.optJSONArray("choices");
                    if (ch != null && ch.length() > 0) {
                        CharSequence[] cs = new CharSequence[ch.length()];
                        for (int q = 0; q < ch.length(); q++) cs[q] = ch.optString(q);
                        rb.setChoices(cs);
                    }
                    ab.addRemoteInput(rb.build());
                }
                b.addAction(ab.build());
                nActions++;
            }
        }

        nm(c).notify(TAG_PREFIX + id, NOTIF_ID, b.build());
        Log.i(BridgeHub.TAG, "notif publicada id=" + id + " pkg=" + pkg + " cat=" + cat
                + " high=" + high + " acciones=" + nActions
                + " len(title)=" + len(title) + " len(text)=" + len(text) + " len(big)=" + len(big)
                + " msgs=" + (msgs == null ? 0 : msgs.length()));
    }

    static void remove(Context c, String id, String why) {
        if (id.isEmpty()) return;
        removedByPhone.put(id, System.currentTimeMillis());
        nm(c).cancel(TAG_PREFIX + id, NOTIF_ID);
        Log.i(BridgeHub.TAG, "notif withdrawn by the phone id=" + id + " why=" + why);
    }

    static void sync(Context c, Set<String> keep) {
        int cancelled = 0;
        StatusBarNotification[] act;
        try {
            act = nm(c).getActiveNotifications();
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "sync: " + t);
            return;
        }
        for (StatusBarNotification sbn : act) {
            String tag = sbn.getTag();
            if (tag == null || !tag.startsWith(TAG_PREFIX) || sbn.getId() != NOTIF_ID) continue;
            String id = tag.substring(TAG_PREFIX.length());
            if (!keep.contains(id)) {
                removedByPhone.put(id, System.currentTimeMillis());
                nm(c).cancel(tag, NOTIF_ID);
                cancelled++;
            }
        }
        Log.i(BridgeHub.TAG, "notif/sync: " + keep.size() + " vigentes, " + cancelled + " canceladas");
    }

    /** true if the id was withdrawn by the phone recently (do not send back dismiss). */
    static boolean wasRemovedByPhone(String id) {
        Long t = removedByPhone.remove(id);
        return t != null && System.currentTimeMillis() - t < 60000;
    }

    // ------------------------------------------------------------------ utilities

    /** Explicit PendingIntent to BridgeActionReceiver. The data URI distinguishes each one
     *  (action, id, index) so they do not clobber each other between notifications. */
    static PendingIntent selfBroadcast(Context c, String action, String id, int idx, boolean mutable) {
        Intent i = new Intent(action)
                .setClass(c, BridgeActionReceiver.class)
                .setData(new Uri.Builder().scheme(SCHEME).authority(action.substring(action.lastIndexOf('.') + 1))
                        .appendPath(id).appendPath(String.valueOf(idx)).build())
                .putExtra(BridgeActionReceiver.EXTRA_ID, id)
                .putExtra(BridgeActionReceiver.EXTRA_INDEX, idx);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (mutable ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    private static int smallIconFor(String cat) {
        if (Notification.CATEGORY_CALL.equals(cat)) return android.R.drawable.sym_call_incoming;
        if (Notification.CATEGORY_MESSAGE.equals(cat)) return android.R.drawable.stat_notify_chat;
        if (Notification.CATEGORY_EMAIL.equals(cat)) return android.R.drawable.sym_action_email;
        if (Notification.CATEGORY_ALARM.equals(cat)) return android.R.drawable.ic_lock_idle_alarm;
        return android.R.drawable.stat_notify_more;
    }

    private static Icon decodeIcon(String b64) {
        if (b64 == null) return null;
        try {
            byte[] png = Base64.decode(b64, Base64.DEFAULT);
            if (png.length == 0 || png.length > 64 * 1024) return null;
            return Icon.createWithData(png, 0, png.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static int len(String s) { return s == null ? 0 : s.length(); }
}
