package shminterop.shmbridge.phone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * FGS (specialUse type) that holds the {@code /__bridge__/} channel on the phone side:
 * the RFCOMM link, the next-alarm notice
 * ({@code ACTION_NEXT_ALARM_CLOCK_CHANGED}) and the call state.
 *
 * <p>Start without a visible activity: {@code CommsBootReceiver} (BOOT_COMPLETED /
 * MY_PACKAGE_REPLACED), the notification listener on connect, or from shell:
 * {@code am start-foreground-service --user 0 -n shminterop.shmbridge.phone/.CommsService}
 * (exported only to holders of {@code android.permission.DUMP}, i.e. shell).</p>
 */
public class CommsService extends Service {

    static final String CHANNEL = "bridge_comms";
    static final int NOTIF_ID = 4101;
    /** True between onCreate and onDestroy (read by {@link StatusProvider}). */
    static volatile boolean running;

    private BroadcastReceiver alarmRx;
    private TelephonyCallback callCb;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        goForeground();
        CommsHub hub = CommsHub.get(this);
        hub.start();
        registerAlarmWatcher(hub);
        registerCallWatcher(hub);
        Log.i(CommsHub.TAG, "CommsService up (" + hub.status() + ")");
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, CallNotice.str(this, "comms_channel"),
                NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("SHM Bridge")
                .setContentText(CallNotice.str(this, "comms_text"))
                .setOngoing(true)
                .setLocalOnly(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(CommsHub.TAG, "startForeground: " + e.getClass().getSimpleName());
        }
    }

    /** AlarmWatcher: every change of the phone's next alarm → alarm/next. */
    private void registerAlarmWatcher(final CommsHub hub) {
        alarmRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                hub.sendAlarmNext();
            }
        };
        IntentFilter f = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(alarmRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(alarmRx, f);
    }

    /** CallWatcher: call state → call/state (requires READ_PHONE_STATE). */
    private void registerCallWatcher(final CommsHub hub) {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            final class Cb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
                @Override public void onCallStateChanged(int state) {
                    hub.onCallState(state == TelephonyManager.CALL_STATE_RINGING ? "ringing"
                            : state == TelephonyManager.CALL_STATE_OFFHOOK ? "offhook" : "idle");
                }
            }
            Cb cb = new Cb();
            tm.registerTelephonyCallback(getMainExecutor(), cb);
            callCb = cb;
        } catch (SecurityException e) {
            Log.w(CommsHub.TAG, "call/state: missing READ_PHONE_STATE (pm grant --user 0 …)");
        } catch (Exception e) {
            Log.w(CommsHub.TAG, "call/state: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // the hub and the link stay alive as long as the process lives (the listener uses them);
        // here only the service's own observers are released
        try { if (alarmRx != null) unregisterReceiver(alarmRx); } catch (Exception ignored) {}
        try {
            if (callCb != null && Build.VERSION.SDK_INT >= 31) {
                getSystemService(TelephonyManager.class).unregisterTelephonyCallback(callCb);
            }
        } catch (Exception ignored) {}
        running = false;
        Log.i(CommsHub.TAG, "CommsService detenido");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
