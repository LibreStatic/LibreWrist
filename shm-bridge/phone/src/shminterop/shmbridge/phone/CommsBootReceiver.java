package shminterop.shmbridge.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts CommsService after boot, after a reinstall and on {@link #ACTION_START} (no visible
 * activity). The receiver is exported without a permission: the patcher app, which cannot hold a
 * permission named with the per-user prefix, sends START once after installing (this clears the
 * stopped state, without which BOOT_COMPLETED is never delivered). Starting the service is
 * idempotent, so any other sender of START gains nothing.
 */
public class CommsBootReceiver extends BroadcastReceiver {
    static final String ACTION_START = "shminterop.shmbridge.START";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !ACTION_START.equals(a)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, CommsService.class));
            Log.i(CommsHub.TAG, "boot: CommsService pedido (" + a + ")");
        } catch (Exception e) {
            Log.w(CommsHub.TAG, "boot: " + e.getClass().getSimpleName());
        }
    }
}
