package shminterop.shmbridge.watch;

import android.telecom.Call;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Observer of calls of the watch's Telecom (InCallService with no UI; the call is still
 * shown by Samsung's dialer). Telecom binds it through the appop
 * {@code MANAGE_ONGOING_CALLS}: {@code adb shell appops set shminterop.shmbridge.watch MANAGE_ONGOING_CALLS allow}.
 *
 * <p>It detects only one case: an outgoing call from the watch (over HFP) that Samsung's HFP client
 * closes with {@link DisconnectCause#CALL_PULLED} without having become active. That happens when the
 * phone does not open the audio within 3 s of dialing (e.g. because it asks to pick a SIM): the
 * call continues on the phone. Then it notifies on the watch and on the phone
 * ({@link CallMirror#onPulled}). It records no numbers or names.</p>
 */
public class CallWatchService extends InCallService {

    /** call → became active. */
    private final Map<Call, Boolean> calls = new HashMap<>();

    private final Call.Callback cb = new Call.Callback() {
        @Override public void onStateChanged(Call call, int state) { check(call, state); }
    };

    @Override
    public void onCallAdded(Call call) {
        calls.put(call, call.getState() == Call.STATE_ACTIVE);
        call.registerCallback(cb);
        Log.i(BridgeHub.TAG, "call: watch Telecom +call dir=" + call.getDetails().getCallDirection()
                + " estado=" + call.getState());
        check(call, call.getState());
    }

    @Override
    public void onCallRemoved(Call call) {
        check(call, call.getState());
        call.unregisterCallback(cb);
        calls.remove(call);
    }

    private void check(Call call, int state) {
        Boolean wasActive = calls.get(call);
        if (wasActive == null) return;
        if (state == Call.STATE_ACTIVE) {
            calls.put(call, true);
            return;
        }
        if (state != Call.STATE_DISCONNECTED) return;
        calls.remove(call); // once per call (onStateChanged and onCallRemoved)
        Call.Details d = call.getDetails();
        DisconnectCause dc = d.getDisconnectCause();
        int code = dc == null ? -1 : dc.getCode();
        boolean outgoing = d.getCallDirection() == Call.Details.DIRECTION_OUTGOING;
        Log.i(BridgeHub.TAG, "call: watch Telecom end cause=" + code + " outgoing=" + outgoing
                + " activa=" + wasActive);
        if (outgoing && !wasActive && code == DisconnectCause.CALL_PULLED) CallMirror.onPulled(this);
    }
}
