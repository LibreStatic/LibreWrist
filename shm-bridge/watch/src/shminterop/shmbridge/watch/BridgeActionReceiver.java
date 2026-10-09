package shminterop.shmbridge.watch;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Receiver for the actions of the mirrored notifications (watch → phone) and for the
 * channel's debug hooks.
 *
 * <p>In the manifest it is exported but protected with {@code android.permission.DUMP}:
 * only the shell (adb) and the app itself (its PendingIntents) can send it broadcasts.</p>
 *
 * <pre>
 * # send a frame to the phone
 * adb shell am broadcast -n shminterop.shmbridge.watch/.BridgeActionReceiver \
 *     -a shminterop.shmbridge.DEBUG_SEND --es path /__bridge__/notif/dismiss --es json '{"id":"x"}'
 * # inject a frame as if it came from the phone
 * adb shell am broadcast -n shminterop.shmbridge.watch/.BridgeActionReceiver \
 *     -a shminterop.shmbridge.DEBUG_RECV --es path /__bridge__/notif/post --es json '{...}'
 * # simulate "outgoing call handed over to the phone" (notice on the watch + call/pulled to the phone)
 * adb shell am broadcast -n shminterop.shmbridge.watch/.BridgeActionReceiver \
 *     -a shminterop.shmbridge.DEBUG_SEND --es path local/call-pulled
 * </pre>
 */
public class BridgeActionReceiver extends BroadcastReceiver {

    static final String PKG = "shminterop.shmbridge.watch";
    static final String ACTION_NOTIF_ACTION = PKG + ".NOTIF_ACTION";
    static final String ACTION_NOTIF_DISMISS = PKG + ".NOTIF_DISMISS";
    static final String ACTION_NOTIF_OPEN = PKG + ".NOTIF_OPEN";
    static final String ACTION_CALL_ANSWER = PKG + ".CALL_ANSWER";
    static final String ACTION_CALL_REJECT = PKG + ".CALL_REJECT";
    public static final String ACTION_DEBUG_SEND = "shminterop.shmbridge.DEBUG_SEND";
    public static final String ACTION_DEBUG_RECV = "shminterop.shmbridge.DEBUG_RECV";

    static final String EXTRA_ID = "br_id";
    static final String EXTRA_INDEX = "br_i";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Context c = context.getApplicationContext();
        BridgeHub hub = BridgeHub.get(c);
        String id = intent.getStringExtra(EXTRA_ID);
        int idx = intent.getIntExtra(EXTRA_INDEX, -1);
        switch (action) {
            case ACTION_NOTIF_ACTION: {
                if (id == null) return;
                Bundle res = RemoteInput.getResultsFromIntent(intent);
                CharSequence text = res == null ? null : res.getCharSequence(BridgeNotifier.REPLY_KEY);
                if (text != null) {
                    hub.send("notif/reply", BridgeHub.obj("id", id, "i", idx, "text", text.toString()));
                    Log.i(BridgeHub.TAG, "respuesta id=" + id + " i=" + idx + " len=" + text.length());
                    markReplied(c, id, text);
                } else {
                    hub.send("notif/action", BridgeHub.obj("id", id, "i", idx));
                    Log.i(BridgeHub.TAG, "action id=" + id + " i=" + idx);
                }
                break;
            }
            case ACTION_NOTIF_DISMISS:
                if (id == null) return;
                if (BridgeNotifier.wasRemovedByPhone(id)) {
                    Log.i(BridgeHub.TAG, "dismissal of id=" + id + " originated on the phone: not sent back");
                    return;
                }
                hub.send("notif/dismiss", BridgeHub.obj("id", id));
                break;
            case ACTION_NOTIF_OPEN:
                if (id == null) return;
                hub.send("notif/open", BridgeHub.obj("id", id));
                break;
            case ACTION_CALL_ANSWER:
                CallMirror.cancel(c);
                // Through local HFP first: this way the phone moves the audio to the watch.
                if (!CallMirror.actLocal(c, true)) hub.send("call/answer", "{}");
                break;
            case ACTION_CALL_REJECT:
                CallMirror.cancel(c);
                if (!CallMirror.actLocal(c, false)) hub.send("call/reject", "{}");
                break;
            case ACTION_DEBUG_SEND: {
                String path = intent.getStringExtra("path");
                String json = intent.getStringExtra("json");
                if (path == null) { Log.w(BridgeHub.TAG, "DEBUG_SEND without path"); return; }
                if ("local/call-pulled".equals(path)) { CallMirror.onPulled(c); break; }
                if (!path.startsWith(FrameMux.BRIDGE_PREFIX)) path = FrameMux.BRIDGE_PREFIX + path;
                Log.i(BridgeHub.TAG, "DEBUG_SEND " + path + " (link=" + hub.isLinked() + ")");
                hub.send(path, json == null ? "{}" : json);
                break;
            }
            case ACTION_DEBUG_RECV: {
                String path = intent.getStringExtra("path");
                String json = intent.getStringExtra("json");
                if (path == null) { Log.w(BridgeHub.TAG, "DEBUG_RECV without path"); return; }
                if (!path.startsWith(FrameMux.BRIDGE_PREFIX)) path = FrameMux.BRIDGE_PREFIX + path;
                hub.inject(path, json);
                break;
            }
            default:
                Log.i(BridgeHub.TAG, "unknown action: " + action);
        }
    }

    /** After a reply through RemoteInput the notification is re-published with the history
     *  so the watch does not keep showing the sending indicator. */
    private static void markReplied(Context c, String id, CharSequence text) {
        try {
            for (StatusBarNotification sbn : BridgeNotifier.nm(c).getActiveNotifications()) {
                if (!(BridgeNotifier.TAG_PREFIX + id).equals(sbn.getTag())
                        || sbn.getId() != BridgeNotifier.NOTIF_ID) continue;
                Notification.Builder b = Notification.Builder.recoverBuilder(c, sbn.getNotification());
                b.setRemoteInputHistory(new CharSequence[]{text});
                b.setOnlyAlertOnce(true);
                BridgeNotifier.nm(c).notify(sbn.getTag(), sbn.getId(), b.build());
                return;
            }
        } catch (Throwable t) {
            Log.w(BridgeHub.TAG, "reply history id=" + id + ": " + t);
        }
    }
}
