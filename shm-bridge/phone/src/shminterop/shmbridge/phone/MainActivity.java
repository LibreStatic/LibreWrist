package shminterop.shmbridge.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal status screen: starts {@link CommsService} (the {@code /__bridge__/} channel) and
 * shows the same state that {@link StatusProvider} exposes.
 */
public class MainActivity extends Activity {

    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // /__bridge__/ communications channel (notifications, alarms, calls): always.
        try {
            startForegroundService(new Intent(this, CommsService.class));
        } catch (Exception e) {
            android.util.Log.w(CommsHub.TAG, "MainActivity: " + e.getClass().getSimpleName());
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("SHM Bridge");
        title.setTextSize(20f);
        root.addView(title);

        state = new TextView(this);
        state.setTextSize(14f);
        state.setPadding(0, pad, 0, pad);
        root.addView(state);

        Button refresh = new Button(this);
        refresh.setText("REFRESH");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refresh(); }
        });
        root.addView(refresh);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        CommsHub hub = CommsHub.peek();
        long age = hub == null ? -1 : hub.lastRxAgeMs();
        String peerCaps = hub == null ? "" : hub.peerCapsCsv();
        String caps = hub == null ? "" : hub.capsCsv();
        state.setText("Service: " + (CommsService.running ? "running" : "stopped")
                + "\nWatch link: " + (hub != null && hub.isLinkUp() ? "up" : "down")
                + "\nWatch hello: " + (hub != null && hub.hasPeerHello() ? "received" : "no")
                + "\nWatch capabilities: " + (peerCaps.isEmpty() ? "-" : peerCaps)
                + "\nOwn capabilities: " + (caps.isEmpty() ? "-" : caps)
                + "\nLast frame: " + (age < 0 ? "never" : age + " ms ago"));
    }
}
