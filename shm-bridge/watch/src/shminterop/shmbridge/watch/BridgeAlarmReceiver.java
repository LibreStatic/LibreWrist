package shminterop.shmbridge.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fire of the managed alarm, Snooze/Dismiss buttons of the alarm notification and
 * re-arming after reboot / app update. Protected in the manifest with
 * {@code android.permission.DUMP}: it receives from the system, the app itself and the shell.
 */
public class BridgeAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_FIRE = BridgeActionReceiver.PKG + ".ALARM_FIRE";
    static final String ACTION_SNOOZE = BridgeActionReceiver.PKG + ".ALARM_SNOOZE";
    static final String ACTION_DISMISS = BridgeActionReceiver.PKG + ".ALARM_DISMISS";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (a == null) return;
        Context c = context.getApplicationContext();
        switch (a) {
            case ACTION_FIRE:
                AlarmMirror.onFire(c);
                break;
            case SamsungAlarm.ACTION_SYNC: {
                long t = AlarmMirror.prefs(c).getLong(AlarmMirror.K_T, 0);
                if (t > 0 && SamsungAlarm.enabled(c)) SamsungAlarm.sync(c, t);
                break;
            }
            case ACTION_SNOOZE:
                AlarmRinger.userAction(c, true);
                break;
            case ACTION_DISMISS:
                AlarmRinger.userAction(c, false);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
            case "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED":
                AlarmMirror.rearm(c);
                break;
            default:
                Log.i(BridgeHub.TAG, "alarm: unknown action " + a);
        }
    }
}
