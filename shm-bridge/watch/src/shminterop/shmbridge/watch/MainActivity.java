package shminterop.shmbridge.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Minimal status screen: starts {@link BridgeService} (the RFCOMM link to the phone) and shows
 * the link state.
 */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView link;
    private TextView hello;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            link.setText("Link: " + RfcommLink.status);
            hello.setText("Phone hello: " + (BridgeHub.get(MainActivity.this).peerHello ? "yes" : "no"));
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setGravity(Gravity.CENTER);
        title.setText("SHM Bridge");
        root.addView(title);

        link = new TextView(this);
        link.setGravity(Gravity.CENTER);
        link.setTextSize(12f);
        root.addView(link);

        hello = new TextView(this);
        hello.setGravity(Gravity.CENTER);
        hello.setTextSize(12f);
        root.addView(hello);

        Button start = new Button(this);
        start.setText("START BRIDGE");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startForegroundService(new Intent(MainActivity.this, BridgeService.class));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        start.setLayoutParams(lp);
        root.addView(start);

        setContentView(root);
        // auto-start the bridge if it is not running yet
        startForegroundService(new Intent(this, BridgeService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
