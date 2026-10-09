package shminterop.shmbridge.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Entry point for messages that do not start services ({@code stopped}, {@code muted}, ...). */
public class LinkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent i) {
        String path = i.getStringExtra(Link.EXTRA_PATH);
        if (path == null) return;
        PendingResult pr = goAsync();
        Link.dispatch(c.getApplicationContext(), path, i.getStringExtra(Link.EXTRA_DATA), pr::finish);
    }
}
