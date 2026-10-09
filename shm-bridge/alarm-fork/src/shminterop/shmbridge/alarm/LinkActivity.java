package shminterop.shmbridge.alarm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Entry point for {@code /c_alarm/started}. It is an activity (no UI) so the app comes to the
 * foreground and can start the foreground service that rings the alarm: Android does not allow
 * that from a background receiver. It is opened by the watch bridge.
 */
public class LinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        String path = i == null ? null : i.getStringExtra(Link.EXTRA_PATH);
        if (path != null) {
            Link.dispatch(getApplicationContext(), path, i.getStringExtra(Link.EXTRA_DATA), () -> {});
        }
        finish();
    }
}
