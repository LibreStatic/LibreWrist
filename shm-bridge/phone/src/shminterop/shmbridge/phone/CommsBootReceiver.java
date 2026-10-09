package shminterop.shmbridge.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts CommsService after boot or a reinstall (no visible activity). */
public class CommsBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, CommsService.class));
            Log.i(CommsHub.TAG, "boot: CommsService pedido (" + a + ")");
        } catch (Exception e) {
            Log.w(CommsHub.TAG, "boot: " + e.getClass().getSimpleName());
        }
    }
}
