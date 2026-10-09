package shminterop.shmbridge.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the user's actions from the fork of Samsung's Alarm app
 * ({@code /w_alarm/dismiss}, {@code /w_alarm/snooze}). Protected with the signature permission
 * {@code shminterop.shmbridge.permission.ALARM_LINK}: only the fork (same signature)
 * can send.
 */
public class AlarmForkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String path = intent.getStringExtra("path");
        String data = intent.getStringExtra("data");
        if (path != null) {
            AlarmFork.onForkAction(context.getApplicationContext(), path, data);
        }
    }
}
