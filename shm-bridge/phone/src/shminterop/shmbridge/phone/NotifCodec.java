package shminterop.shmbridge.phone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Translation StatusBarNotification → {@code /__bridge__/notif/*} messages and
 * {@code /__bridge__/alarm/ring}. Nothing here is logged: the bodies only travel over
 * the link (docs/bridge-comms-protocol.md §Privacy).
 */
final class NotifCodec {

    private NotifCodec() {}

    static final int ICON_MAX = 4096;
    static final Pattern SNOOZE = Pattern.compile(
            "snooze|pospon|repetir|aplazar|dormir|sp[aä]ter|r[eé]p[eé]ter|rinvia|贪睡|稍后|再响",
            Pattern.CASE_INSENSITIVE);
    static final Pattern DISMISS = Pattern.compile(
            "dismiss|descart|detener|stop|apagar|parar|desactivar|cerrar|close|ignorar",
            Pattern.CASE_INSENSITIVE);
    static final Pattern DECLINE = Pattern.compile(
            "decline|reject|rechaz|colgar|cortar|finalizar|end call|hang", Pattern.CASE_INSENSITIVE);

    /** Stable id from the contract: SHA-1 of the StatusBarNotification key, 16 hex. */
    static String idOf(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    /** Contract filter: own package, ongoing (except call), group summaries,
     *  MIN importance and FLAG_LOCAL_ONLY. */
    static boolean passes(Context ctx, StatusBarNotification sbn, NotificationListenerService.RankingMap rm) {
        if (sbn == null) return false;
        Notification n = sbn.getNotification();
        if (n == null) return false;
        if (ctx.getPackageName().equals(sbn.getPackageName())) return false;
        int f = n.flags;
        if ((f & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((f & Notification.FLAG_LOCAL_ONLY) != 0) return false;
        if ((f & Notification.FLAG_ONGOING_EVENT) != 0
                && !Notification.CATEGORY_CALL.equals(n.category)) return false;
        NotificationListenerService.Ranking r = ranking(sbn, rm);
        if (r != null && r.getImportance() == NotificationManager.IMPORTANCE_MIN) return false;
        return true;
    }

    static NotificationListenerService.Ranking ranking(StatusBarNotification sbn,
                                                      NotificationListenerService.RankingMap rm) {
        if (rm == null) return null;
        NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
        return rm.getRanking(sbn.getKey(), r) ? r : null;
    }

    /**
     * Ringing alarm of the Clock app: category alarm with a full-screen intent or the insistent
     * flag, or ongoing with a snooze action. Ongoing alone is not enough: the "upcoming alarm"
     * reminder (and the "snoozed" entry) of some Clock apps is ongoing, category alarm too.
     */
    static boolean isRingingAlarm(StatusBarNotification sbn, String clockPkg) {
        Notification n = clockAlarm(sbn, clockPkg);
        if (n == null) return false;
        if (n.fullScreenIntent != null || (n.flags & Notification.FLAG_INSISTENT) != 0) return true;
        return (n.flags & Notification.FLAG_ONGOING_EVENT) != 0 && actionIndex(n, SNOOZE) >= 0;
    }

    /** Signals isRingingAlarm() looked at, for the log (no content); null if not a Clock alarm. */
    static String alarmSignals(StatusBarNotification sbn, String clockPkg) {
        Notification n = clockAlarm(sbn, clockPkg);
        if (n == null) return null;
        return "fsi=" + (n.fullScreenIntent != null)
                + " insistent=" + ((n.flags & Notification.FLAG_INSISTENT) != 0)
                + " ongoing=" + ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0)
                + " snooze=" + (actionIndex(n, SNOOZE) >= 0);
    }

    private static Notification clockAlarm(StatusBarNotification sbn, String clockPkg) {
        if (sbn == null) return null;
        Notification n = sbn.getNotification();
        if (n == null || !Notification.CATEGORY_ALARM.equals(n.category)) return null;
        return isClockPackage(sbn.getPackageName(), clockPkg) ? n : null;
    }

    static boolean isClockPackage(String pkg, String clockPkg) {
        if (pkg == null) return false;
        if (pkg.equals(clockPkg)) return true;
        return pkg.contains("deskclock") || pkg.endsWith(".clock") || pkg.contains(".clockpackage");
    }

    static String str(CharSequence cs, int max) {
        if (cs == null) return null;
        String s = cs.toString();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String appLabel(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    static int actionIndex(Notification n, Pattern p) {
        if (n == null || n.actions == null) return -1;
        for (int i = 0; i < n.actions.length; i++) {
            CharSequence t = n.actions[i].title;
            if (t != null && p.matcher(t).find()) return i;
        }
        return -1;
    }

    static JSONObject post(Context ctx, StatusBarNotification sbn,
                           NotificationListenerService.RankingMap rm) throws JSONException {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras == null ? new Bundle() : n.extras;
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("id", idOf(sbn.getKey()));
        o.put("pkg", sbn.getPackageName());
        o.put("app", appLabel(ctx, sbn.getPackageName()));
        o.put("ts", n.when > 0 ? n.when : sbn.getPostTime());
        o.put("title", nz(str(ex.getCharSequence(Notification.EXTRA_TITLE_BIG), 512),
                str(ex.getCharSequence(Notification.EXTRA_TITLE), 512)));
        o.put("text", nz(str(ex.getCharSequence(Notification.EXTRA_TEXT), 2000), ""));
        o.put("big", nz(str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT), 4000), ""));
        o.put("sub", nz(str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT), 256), ""));
        o.put("cat", n.category == null ? "" : n.category);
        JSONArray msgs = messages(ex);
        String tpl = ex.getString(Notification.EXTRA_TEMPLATE);
        boolean conv = msgs.length() > 0
                || (tpl != null && tpl.endsWith("MessagingStyle"));
        o.put("conv", conv);
        o.put("msgs", msgs);
        o.put("group", n.getGroup() == null ? "" : n.getGroup());
        JSONObject ch = new JSONObject();
        ch.put("id", n.getChannelId() == null ? "" : n.getChannelId());
        NotificationListenerService.Ranking r = ranking(sbn, rm);
        NotificationChannel nc = r == null ? null : r.getChannel();
        ch.put("name", nc == null || nc.getName() == null ? "" : nc.getName().toString());
        ch.put("imp", r == null ? NotificationManager.IMPORTANCE_DEFAULT : r.getImportance());
        o.put("ch", ch);
        String icon = icon(ctx, sbn);
        if (icon != null) o.put("icon", icon);
        JSONArray acts = new JSONArray();
        if (n.actions != null) {
            for (int i = 0; i < n.actions.length; i++) {
                Notification.Action a = n.actions[i];
                JSONObject ja = new JSONObject();
                ja.put("i", i);
                ja.put("label", a.title == null ? "" : str(a.title, 64));
                RemoteInput[] ris = a.getRemoteInputs();
                boolean reply = false;
                JSONArray choices = new JSONArray();
                if (ris != null) {
                    for (RemoteInput ri : ris) {
                        if (ri.getAllowFreeFormInput()) reply = true;
                        CharSequence[] cs = ri.getChoices();
                        if (cs != null) for (CharSequence c : cs) if (c != null) choices.put(str(c, 64));
                    }
                }
                ja.put("reply", reply);
                ja.put("choices", choices);
                acts.put(ja);
            }
        }
        o.put("actions", acts);
        o.put("dismissible", sbn.isClearable());
        // contract ceiling: ≤ 64 KB per frame
        if (o.toString().getBytes(StandardCharsets.UTF_8).length > BridgeLink.MAX_JSON - 1024) {
            o.remove("icon");
            o.put("big", "");
            o.put("msgs", tail(msgs, 3));
        }
        return o;
    }

    private static JSONArray tail(JSONArray a, int n) {
        JSONArray r = new JSONArray();
        for (int i = Math.max(0, a.length() - n); i < a.length(); i++) r.put(a.opt(i));
        return r;
    }

    private static String nz(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    private static JSONArray messages(Bundle ex) throws JSONException {
        JSONArray out = new JSONArray();
        Parcelable[] arr;
        try {
            arr = android.os.Build.VERSION.SDK_INT >= 33
                    ? ex.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class)
                    : ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        } catch (Throwable t) {
            arr = null;
        }
        if (arr == null) return out;
        java.util.List<Notification.MessagingStyle.Message> ms =
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr);
        int from = Math.max(0, ms.size() - 10);
        for (int i = from; i < ms.size(); i++) {
            Notification.MessagingStyle.Message m = ms.get(i);
            JSONObject jm = new JSONObject();
            Person p = m.getSenderPerson();
            jm.put("from", p == null || p.getName() == null ? "" : str(p.getName(), 128));
            jm.put("text", m.getText() == null ? "" : str(m.getText(), 1000));
            jm.put("ts", m.getTimestamp());
            out.put(jm);
        }
        return out;
    }

    /** Large icon (or the app's) as base64 PNG, ≤ 4 KB; null if it does not fit. */
    private static String icon(Context ctx, StatusBarNotification sbn) {
        Drawable d = null;
        try {
            Icon li = sbn.getNotification().getLargeIcon();
            if (li != null) d = li.loadDrawable(ctx);
        } catch (Throwable ignored) {}
        if (d == null) {
            try {
                d = ctx.getPackageManager().getApplicationIcon(sbn.getPackageName());
            } catch (Throwable ignored) {}
        }
        if (d == null) return null;
        for (int px : new int[]{48, 36, 24}) {
            try {
                Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                    c.drawBitmap(Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), px, px, true), 0, 0, null);
                } else {
                    d.setBounds(0, 0, px, px);
                    d.draw(c);
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.PNG, 100, bo);
                b.recycle();
                if (bo.size() <= ICON_MAX) return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static JSONObject alarmRing(StatusBarNotification sbn) throws JSONException {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras == null ? new Bundle() : n.extras;
        JSONObject o = new JSONObject();
        o.put("id", idOf(sbn.getKey()));
        o.put("ts", sbn.getPostTime());
        String label = str(ex.getCharSequence(Notification.EXTRA_TITLE), 128);
        if (label != null && !label.isEmpty()) o.put("label", label);
        o.put("canSnooze", actionIndex(n, SNOOZE) >= 0);
        return o;
    }
}
