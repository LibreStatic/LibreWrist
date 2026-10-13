package shminterop.shmbridge.phone;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.AlarmClock;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phone side of the {@code /__bridge__/} channel (docs/bridge-comms-protocol.md).
 *
 * <p>A single thread ({@code bridge-comms-hub}) processes everything: watch frames, events from the
 * notification listener, from the alarm and from calls. This keeps the order stable and nothing
 * network-related runs on the main thread. Logs: tag {@code SHMBridgeComms}, only id, package,
 * path and lengths (never titles, texts, names or numbers).</p>
 *
 * <p>Configuration (prefs {@code comms}, changed with {@code CommsDebugReceiver}, path
 * {@code config}):</p>
 * <ul>
 *   <li>{@code caps}: capabilities that are mirrored, comma-separated. Default {@code alarm}:
 *   with the watch paired through Galaxy Wearable, notifications and calls already arrive
 *   natively, and mirroring them again would duplicate them. {@code notif,alarm,call} = everything.</li>
 * </ul>
 */
final class CommsHub implements BridgeLink.Listener {

    static final String TAG = BridgeLink.TAG;
    static final String P = BridgeLink.PREFIX;
    static final long HELLO_MIN_MS = 3000;
    static final String PREFS = "comms";
    static final String DEFAULT_CAPS = "alarm";

    private static volatile CommsHub instance;

    static CommsHub get(Context ctx) {
        CommsHub h = instance;
        if (h == null) {
            synchronized (CommsHub.class) {
                h = instance;
                if (h == null) instance = h = new CommsHub(ctx.getApplicationContext());
            }
        }
        return h;
    }

    /** The hub if it already exists, else null. Never creates it and never starts the link
     *  (used by {@link StatusProvider}, which must not have side effects). */
    static CommsHub peek() { return instance; }

    final Context ctx;
    final Handler handler;
    final BridgeLink link;
    private long lastHelloSent, lastStateSent, linkUpAt;
    /** Cap on notif/post per sync (the most recent ones); notif/sync lists all. */
    static final int SYNC_MAX_POSTS = 40;
    /** id → key of the mirrored notifications (for dismiss/action/reply/open). */
    private final Map<String, String> keys = new ConcurrentHashMap<>();
    /** ids of ringing alarms (notification of the Clock app). */
    private final Set<String> ringing = ConcurrentHashMap.newKeySet();
    private volatile String callState = "idle";
    private String clockPkg;
    private volatile Set<String> caps;
    /** A watch {@code /__bridge__/hello} was received on the current link. */
    private volatile boolean peerHello;
    /** Capabilities announced in that hello (csv, known names only), "" if none. */
    private volatile String peerCaps = "";

    private CommsHub(Context app) {
        this.ctx = app;
        HandlerThread ht = new HandlerThread("bridge-comms-hub");
        ht.start();
        this.handler = new Handler(ht.getLooper());
        SharedPreferences sp = prefs(app);
        this.link = new BridgeLink(this, BridgeLink.rfcomm(app));
        this.caps = parseCaps(sp.getString("caps", DEFAULT_CAPS));
        Log.i(TAG, "config: " + link.describe() + ", caps " + caps);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Set<String> parseCaps(String csv) {
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (String c : csv.split(",")) {
            c = c.trim();
            if (c.equals("notif") || c.equals("alarm") || c.equals("call")) out.add(c);
        }
        return out;
    }

    /** CommsDebugReceiver path=config: stores caps; takes effect immediately. */
    void configure(String capsCsv) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (capsCsv != null) {
            e.putString("caps", capsCsv);
            caps = parseCaps(capsCsv);
        }
        e.apply();
        Log.i(TAG, "config: saved (caps " + caps + ")");
    }

    /** Own capabilities as csv, in the canonical order. */
    String capsCsv() {
        return joinKnown(caps);
    }

    boolean hasPeerHello() { return peerHello; }

    String peerCapsCsv() { return peerCaps; }

    long lastRxAgeMs() { return link.lastRxAgeMs(); }

    boolean isLinkUp() { return link.isUp(); }

    private static String joinKnown(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String c : new String[] {"notif", "alarm", "call"}) {
            if (!set.contains(c)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        return sb.toString();
    }

    /** Capability of a channel path ("notif/post" → notif); null = always allowed. */
    private static String capOf(String path) {
        if (!path.startsWith(P)) return null;
        String rest = path.substring(P.length());
        int slash = rest.indexOf('/');
        String head = slash < 0 ? rest : rest.substring(0, slash);
        return head.equals("notif") || head.equals("alarm") || head.equals("call") ? head : null;
    }

    void start() {
        link.start();
    }

    // ---------------------------------------------------------------- link

    /** When the link comes up: own hello. The state goes out with the watch's hello; if none
     *  arrived within 4 s (e.g. lost at the host), it is sent anyway. */
    @Override public void onLinkUp() {
        resetPeer();
        handler.post(() -> {
            final long upAt = System.currentTimeMillis();
            linkUpAt = upAt;
            sendHello();
            handler.postDelayed(() -> {
                if (link.isUp() && linkUpAt == upAt && lastStateSent < upAt) {
                    Log.i(TAG, "no hello from the watch in 4 s: sending state anyway");
                    sendState();
                }
            }, 4000);
        });
    }

    @Override public void onLinkDown() {
        resetPeer();
    }

    private void resetPeer() {
        peerHello = false;
        peerCaps = "";
    }

    @Override public void onFrame(final String path, final byte[] data) {
        handler.post(() -> dispatch(path, data));
    }

    boolean send(String path, JSONObject o) {
        String cap = capOf(path);
        if (cap != null && !caps.contains(cap)) return false;  // capability turned off
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        if (b.length > BridgeLink.MAX_JSON) {
            Log.w(TAG, "tx " + path + " descartada: " + b.length + " B > 64 KB");
            return false;
        }
        boolean ok = link.send(path, b);
        Log.d(TAG, "tx " + path + " (" + b.length + " B)" + (ok ? "" : " no link"));
        return ok;
    }

    private void sendHello() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("role", "phone");
            JSONArray ca = new JSONArray();
            for (String c : new String[] {"notif", "alarm", "call"}) if (caps.contains(c)) ca.put(c);
            o.put("caps", ca);
            if (send(P + "hello", o)) lastHelloSent = System.currentTimeMillis();
        } catch (Exception e) {
            Log.w(TAG, "hello: " + e);
        }
    }

    /** State messages (idempotent): sent on connect and on every hello received. */
    private void sendState() {
        lastStateSent = System.currentTimeMillis();
        sendNotifSync();
        sendAlarmNext();
        sendCallState(callState);
    }

    // ---------------------------------------------------------------- watch → phone

    private void dispatch(String path, byte[] data) {
        JSONObject o;
        try {
            o = data.length == 0 ? new JSONObject() : new JSONObject(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "rx " + path + " (" + data.length + " B): invalid JSON");
            return;
        }
        Log.i(TAG, "rx " + path + " (" + data.length + " B)" + (o.has("id") ? " id=" + o.optString("id") : ""));
        switch (path) {
            case P + "hello":
                recordPeerHello(o);
                // contract rule: reply with own hello only if we did not send one within 3 s
                if (System.currentTimeMillis() - lastHelloSent > HELLO_MIN_MS) sendHello();
                sendState();
                break;
            case P + "notif/dismiss": notifDismiss(o.optString("id")); break;
            case P + "notif/action": notifAction(o.optString("id"), o.optInt("i", -1), null); break;
            case P + "notif/reply": notifAction(o.optString("id"), o.optInt("i", -1), o.optString("text", "")); break;
            case P + "notif/open": notifOpen(o.optString("id")); break;
            case P + "alarm/snooze": alarmControl(o.optString("id"), true, 0); break;
            case P + "alarm/dismiss": alarmControl(o.optString("id"), false, 0); break;
            case P + "call/answer": callControl(true); break;
            case P + "call/reject": callControl(false); break;
            case P + "call/pulled": CallNotice.show(ctx); break;
            default: Log.i(TAG, "rx " + path + ": no handler");
        }
    }

    private void recordPeerHello(JSONObject o) {
        Set<String> pc = ConcurrentHashMap.newKeySet();
        JSONArray ca = o.optJSONArray("caps");
        if (ca != null) {
            for (int i = 0; i < ca.length(); i++) pc.addAll(parseCaps(ca.optString(i, "")));
        }
        peerCaps = joinKnown(pc);
        peerHello = true;
    }

    private StatusBarNotification findById(String id) {
        BridgeNotificationListener l = BridgeNotificationListener.instance;
        if (l == null || id == null || id.isEmpty()) return null;
        StatusBarNotification[] act;
        try {
            act = l.getActiveNotifications();
        } catch (Exception e) {
            return null;
        }
        if (act == null) return null;
        String key = keys.get(id);
        for (StatusBarNotification s : act) {
            if (key != null ? key.equals(s.getKey()) : id.equals(NotifCodec.idOf(s.getKey()))) return s;
        }
        if (key != null) {  // the stored key no longer exists: look up by hash
            for (StatusBarNotification s : act) if (id.equals(NotifCodec.idOf(s.getKey()))) return s;
        }
        return null;
    }

    private void notifDismiss(String id) {
        BridgeNotificationListener l = BridgeNotificationListener.instance;
        StatusBarNotification s = findById(id);
        if (l == null || s == null) {
            Log.i(TAG, "notif/dismiss id=" + id + ": not active (or no listener)");
            return;
        }
        try {
            l.cancelNotification(s.getKey());
            Log.i(TAG, "notif/dismiss id=" + id + " pkg=" + s.getPackageName() + ": cancelada");
        } catch (Exception e) {
            Log.w(TAG, "notif/dismiss id=" + id + ": " + e.getClass().getSimpleName());
        }
    }

    /** Options so the PendingIntent uses the own BAL exemption (appop
     *  SYSTEM_ALERT_WINDOW): since Android 14 the sender has to opt in explicitly. */
    static Bundle balOptions() {
        if (Build.VERSION.SDK_INT < 34) return null;
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        return o.toBundle();
    }

    private boolean sendPi(PendingIntent pi, Intent fill) {
        try {
            pi.send(ctx, 0, fill, null, null, null, balOptions());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "PendingIntent: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private void notifAction(String id, int i, String replyText) {
        StatusBarNotification s = findById(id);
        String what = replyText == null ? "notif/action" : "notif/reply";
        if (s == null) {
            Log.i(TAG, what + " id=" + id + ": not active");
            return;
        }
        Notification n = s.getNotification();
        if (n.actions == null || i < 0 || i >= n.actions.length || n.actions[i].actionIntent == null) {
            Log.i(TAG, what + " id=" + id + " i=" + i + ": no such action");
            return;
        }
        Notification.Action a = n.actions[i];
        Intent fill = null;
        if (replyText != null) {
            RemoteInput[] ris = a.getRemoteInputs();
            if (ris == null || ris.length == 0) {
                Log.i(TAG, what + " id=" + id + " i=" + i + ": the action does not accept a reply");
                return;
            }
            fill = new Intent();
            Bundle res = new Bundle();
            for (RemoteInput ri : ris) res.putCharSequence(ri.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(ris, fill, res);
            RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
        }
        boolean ok = sendPi(a.actionIntent, fill);
        Log.i(TAG, what + " id=" + id + " pkg=" + s.getPackageName() + " i=" + i
                + (replyText != null ? " len=" + replyText.length() : "") + (ok ? ": sent" : ": failed"));
    }

    private void notifOpen(String id) {
        StatusBarNotification s = findById(id);
        if (s == null) {
            Log.i(TAG, "notif/open id=" + id + ": not active");
            return;
        }
        PendingIntent ci = s.getNotification().contentIntent;
        boolean ok = ci != null && sendPi(ci, null);
        if (!ok) {
            try {  // no contentIntent: open the app (also with the BAL exemption)
                Intent li = ctx.getPackageManager().getLaunchIntentForPackage(s.getPackageName());
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    ok = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "notif/open launcher: " + e.getClass().getSimpleName());
            }
        }
        Log.i(TAG, "notif/open id=" + id + " pkg=" + s.getPackageName() + (ok ? ": sent" : ": failed"));
    }

    // ---------------------------------------------------------------- notifications

    void onNotifPosted(StatusBarNotification sbn, android.service.notification.NotificationListenerService.RankingMap rm) {
        handler.post(() -> {
            try {
                String id = NotifCodec.idOf(sbn.getKey());
                if (NotifCodec.isRingingAlarm(sbn, clockPackage())) {
                    keys.put(id, sbn.getKey());
                    if (ringing.add(id)) {
                        send(P + "alarm/ring", NotifCodec.alarmRing(sbn));
                        Log.i(TAG, "alarm/ring id=" + id + " pkg=" + sbn.getPackageName());
                    }
                    return;
                }
                if (ringing.remove(id)) sendAlarmStop(id);  // same key, no longer ringing (e.g. snoozed)
                if (!caps.contains("notif")) return;
                if (!NotifCodec.passes(ctx, sbn, rm)) {
                    if (keys.remove(id) != null) sendRemove(id, "cancel");  // no longer qualifies
                    return;
                }
                keys.put(id, sbn.getKey());
                JSONObject o = NotifCodec.post(ctx, sbn, rm);
                send(P + "notif/post", o);
                Log.i(TAG, "notif/post id=" + id + " pkg=" + sbn.getPackageName()
                        + " cat=" + o.optString("cat") + " acciones=" + o.optJSONArray("actions").length());
            } catch (Exception e) {
                Log.w(TAG, "notif/post: " + e.getClass().getSimpleName());
            }
        });
    }

    void onNotifRemoved(StatusBarNotification sbn, int reason) {
        handler.post(() -> {
            String id = NotifCodec.idOf(sbn.getKey());
            if (ringing.remove(id)) {
                sendAlarmStop(id);
                keys.remove(id);
                return;
            }
            if (keys.remove(id) == null) return;  // never mirrored
            String why;
            switch (reason) {
                case android.service.notification.NotificationListenerService.REASON_CLICK: why = "click"; break;
                case android.service.notification.NotificationListenerService.REASON_APP_CANCEL:
                case android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL:
                case android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED:
                case android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED:
                    why = "app"; break;
                default: why = "cancel";
            }
            sendRemove(id, why);
        });
    }

    void onListenerConnected() {
        handler.post(this::sendNotifSync);
    }

    private void sendRemove(String id, String why) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("why", why);
            send(P + "notif/remove", o);
            Log.i(TAG, "notif/remove id=" + id + " why=" + why);
        } catch (Exception ignored) {}
    }

    /** Re-publishes the active ones that pass the filter and sends the full list (notif/sync). */
    private void sendNotifSync() {
        BridgeNotificationListener l = BridgeNotificationListener.instance;
        if (l == null) {
            Log.i(TAG, "notif/sync: listener not connected (cmd notification allow_listener …)");
            return;
        }
        if (!link.isUp()) return;
        try {
            StatusBarNotification[] act = l.getActiveNotifications();
            android.service.notification.NotificationListenerService.RankingMap rm = l.getCurrentRanking();
            JSONArray ids = new JSONArray();
            keys.clear();
            int n = 0, posted = 0;
            if (act != null) {
                // most recent first: if there are more than SYNC_MAX_POSTS, the old ones only go in ids
                java.util.Arrays.sort(act, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
                for (StatusBarNotification s : act) {
                    String id = NotifCodec.idOf(s.getKey());
                    if (NotifCodec.isRingingAlarm(s, clockPackage())) {
                        keys.put(id, s.getKey());
                        ringing.add(id);
                        send(P + "alarm/ring", NotifCodec.alarmRing(s));
                        continue;
                    }
                    if (!caps.contains("notif") || !NotifCodec.passes(ctx, s, rm)) continue;
                    keys.put(id, s.getKey());
                    if (posted < SYNC_MAX_POSTS) {
                        send(P + "notif/post", NotifCodec.post(ctx, s, rm));
                        posted++;
                    }
                    ids.put(id);
                    n++;
                }
            }
            JSONObject o = new JSONObject();
            o.put("ids", ids);
            send(P + "notif/sync", o);
            Log.i(TAG, "notif/sync: " + n + " activas, " + posted + " publicadas");
        } catch (Exception e) {
            Log.w(TAG, "notif/sync: " + e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- alarms

    String clockPackage() {
        if (clockPkg == null) {
            try {
                android.content.pm.ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                        new Intent(AlarmClock.ACTION_SET_ALARM), 0);
                clockPkg = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
            } catch (Exception e) {
                clockPkg = "";
            }
        }
        return clockPkg;
    }

    /**
     * alarm/next: the phone's next alarm, used by the watch as a fallback when no ring arrives.
     * {@code getNextAlarmClock()} returns the next {@code setAlarmClock()} of ANY app (reminders,
     * calendars, sleep trackers...), whose fire never posts a Clock-app ring; mirroring it made the
     * watch ring on its own at unrelated times. Only the Clock app's alarm is mirrored: when another
     * app owns the next alarm clock, t=null (the Clock alarm still rings on the watch through its
     * notification while the link is up).
     */
    void sendAlarmNext() {
        handler.post(() -> {
            try {
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                AlarmManager.AlarmClockInfo ai = am == null ? null : am.getNextAlarmClock();
                String owner = ai == null || ai.getShowIntent() == null
                        ? null : ai.getShowIntent().getCreatorPackage();
                boolean clock = owner != null && NotifCodec.isClockPackage(owner, clockPackage());
                JSONObject o = new JSONObject();
                o.put("t", clock ? (Object) ai.getTriggerTime() : JSONObject.NULL);
                send(P + "alarm/next", o);
                Log.i(TAG, "alarm/next t=" + (ai == null ? "null" : String.valueOf(ai.getTriggerTime()))
                        + " owner=" + owner + (ai != null && !clock ? " (not the Clock app, not mirrored)" : ""));
            } catch (Exception e) {
                Log.w(TAG, "alarm/next: " + e.getClass().getSimpleName());
            }
        });
    }

    private void sendAlarmStop(String id) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            send(P + "alarm/stop", o);
            Log.i(TAG, "alarm/stop id=" + id);
        } catch (Exception ignored) {}
    }

    /**
     * alarm/snooze|dismiss: in order (1) the action of the Clock app's notification,
     * (2) ACTION_SNOOZE_ALARM / ACTION_DISMISS_ALARM (ALARM_SEARCH_MODE_NEXT) with the
     * BAL exemption, (3) cancelNotification. After each step it checks at 2.5 s whether the
     * alarm is still ringing; if so, it moves on to the next one.
     */
    private void alarmControl(final String id, final boolean snooze, final int step) {
        final String what = snooze ? "alarm/snooze" : "alarm/dismiss";
        StatusBarNotification s = findById(id);
        if (s == null || !NotifCodec.isRingingAlarm(s, clockPackage())) {
            if (step == 0) Log.i(TAG, what + " id=" + id + ": the alarm is not ringing");
            else Log.i(TAG, what + " id=" + id + ": resolved at step " + step);
            return;
        }
        boolean tried = false;
        switch (step) {
            case 0: {
                int i = NotifCodec.actionIndex(s.getNotification(), snooze ? NotifCodec.SNOOZE : NotifCodec.DISMISS);
                if (i >= 0) tried = sendPi(s.getNotification().actions[i].actionIntent, null);
                Log.i(TAG, what + " id=" + id + " step 1 (notification action): "
                        + (i < 0 ? "no action" : tried ? "sent" : "failed"));
                break;
            }
            case 1: {
                try {
                    Intent it = new Intent(snooze ? AlarmClock.ACTION_SNOOZE_ALARM : AlarmClock.ACTION_DISMISS_ALARM);
                    if (!snooze) it.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT);
                    String cp = clockPackage();
                    if (!cp.isEmpty()) it.setPackage(cp);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(it);
                    tried = true;
                } catch (Exception e) {
                    Log.w(TAG, what + " paso 2: " + e.getClass().getSimpleName());
                }
                Log.i(TAG, what + " id=" + id + " step 2 (AlarmClock intent): " + (tried ? "sent" : "failed"));
                break;
            }
            case 2: {
                BridgeNotificationListener l = BridgeNotificationListener.instance;
                if (l != null) {
                    try {
                        l.cancelNotification(s.getKey());
                        tried = true;
                    } catch (Exception ignored) {}
                }
                Log.i(TAG, what + " id=" + id + " step 3 (cancelNotification): " + (tried ? "done" : "failed"));
                return;
            }
            default:
                return;
        }
        handler.postDelayed(() -> alarmControl(id, snooze, step + 1), tried ? 2500 : 0);
    }

    // ---------------------------------------------------------------- calls

    void onCallState(String s) {
        handler.post(() -> {
            if (s.equals(callState)) return;
            callState = s;
            sendCallState(s);
        });
    }

    private void sendCallState(String s) {
        try {
            JSONObject o = new JSONObject();
            o.put("s", s);
            send(P + "call/state", o);
            Log.i(TAG, "call/state " + s);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void callControl(boolean answer) {
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        boolean ok = false;
        try {
            if (answer) {
                tm.acceptRingingCall();
                ok = true;
            } else {
                ok = tm.endCall();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "call: missing ANSWER_PHONE_CALLS (pm grant --user 0 …)");
        } catch (Exception e) {
            Log.w(TAG, "call: " + e.getClass().getSimpleName());
        }
        if (!answer && !ok) {
            // fallback: the "reject" action of the mirrored call notification
            for (Map.Entry<String, String> en : keys.entrySet()) {
                StatusBarNotification s = findById(en.getKey());
                if (s == null || !Notification.CATEGORY_CALL.equals(s.getNotification().category)) continue;
                int i = NotifCodec.actionIndex(s.getNotification(), NotifCodec.DECLINE);
                if (i >= 0) ok = sendPi(s.getNotification().actions[i].actionIntent, null);
                break;
            }
        }
        Log.i(TAG, (answer ? "call/answer" : "call/reject") + (ok ? ": done" : ": failed"));
    }

    // ---------------------------------------------------------------- debugging

    /** CommsDebugReceiver: sends a frame to the watch, or (inject) processes it as if
     *  it had arrived from the watch. */
    void debug(final String path, final String json, final boolean inject) {
        handler.post(() -> {
            byte[] b = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            if (inject) {
                Log.i(TAG, "debug: injecting " + path + " (" + b.length + " B) as if it came from the watch");
                dispatch(path, b);
            } else {
                boolean ok = link.send(path, b);
                Log.i(TAG, "debug: tx " + path + " (" + b.length + " B)" + (ok ? "" : " no link"));
            }
        });
    }

    String status() {
        return "link " + (link.isUp() ? "up" : "down") + " (" + link.describe() + ")"
                + "; hello " + (peerHello ? "yes" : "no") + " peer caps [" + peerCaps + "]"
                + "; caps " + caps
                + "; listener " + (BridgeNotificationListener.instance != null ? "connected" : "not connected")
                + "; mirrored " + keys.size() + "; call " + callState;
    }
}
