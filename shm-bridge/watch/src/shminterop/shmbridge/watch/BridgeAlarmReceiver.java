package shminterop.shmbridge.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fire of the managed alarm, Snooze/Dismiss buttons of the alarm notification and
 * re-arming after reboot / app update, which also start the link service. Protected in the manifest with
 * {@code android.permission.DUMP}: it receives from the system, the app itself and the shell.
 *
 * <p>{@link #ACTION_START} may carry the boolean extra {@link #EXTRA_ALARMS}; {@code false} turns
 * alarm sync off entirely (see {@link AlarmMirror}). Without the extra the setting is unchanged.</p>
 */
public class BridgeAlarmReceiver extends BroadcastReceiver {

    /** Starts the link ({@link CompanionLink#startLink}); sent once by the installer (clears the stopped state) via adb, optionally with the {@code alarms} switch. */
    static final String ACTION_START = "shminterop.shmbridge.START";
    /** Boolean extra of {@link #ACTION_START}: {@code false} turns alarm sync off ({@link AlarmMirror#syncEnabled}). */
    static final String EXTRA_ALARMS = "alarms";
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
                if (t > 0 && AlarmMirror.syncEnabled(c) && SamsungAlarm.enabled(c)) SamsungAlarm.sync(c, t);
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
                AlarmMirror.rearm(c);
                CompanionLink.startLink(c, a);
                break;
            case ACTION_START:
                // Optional boolean extra from the installer; absent keeps the current setting (default on).
                if (intent.hasExtra(EXTRA_ALARMS)) {
                    AlarmMirror.setSyncEnabled(c, intent.getBooleanExtra(EXTRA_ALARMS, true));
                }
                CompanionLink.startLink(c, a);
                break;
            case "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED":
                AlarmMirror.rearm(c);
                break;
            default:
                Log.i(BridgeHub.TAG, "alarm: unknown action " + a);
        }
    }
}
