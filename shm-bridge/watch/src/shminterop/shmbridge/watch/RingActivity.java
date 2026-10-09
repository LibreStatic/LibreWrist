package shminterop.shmbridge.watch;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ringing alarm screen (full-screen intent). Sound and vibration live in
 * {@link AlarmRinger}; this activity only shows the state and offers Snooze/Dismiss.
 * Fixed portrait orientation (manifest): on this watch rotation must stay locked.
 */
public class RingActivity extends Activity implements AlarmRinger.UiListener {

    private TextView labelView;
    private Button snooze;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView time = new TextView(this);
        time.setGravity(Gravity.CENTER);
        time.setTextSize(30f);
        time.setTextColor(Color.WHITE);
        time.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        root.addView(time);

        labelView = new TextView(this);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextSize(14f);
        labelView.setTextColor(Color.LTGRAY);
        root.addView(labelView);

        snooze = new Button(this);
        snooze.setText("Posponer");
        snooze.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { AlarmRinger.userAction(RingActivity.this, true); }
        });
        root.addView(snooze);

        Button dismiss = new Button(this);
        dismiss.setText("Descartar");
        dismiss.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { AlarmRinger.userAction(RingActivity.this, false); }
        });
        root.addView(dismiss);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AlarmRinger.setUi(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        AlarmRinger.clearUi(this);
        super.onDestroy();
    }

    @Override
    public void onRingStateChanged() {
        runOnUiThread(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    private void refresh() {
        if (!AlarmRinger.isRinging()) {
            finish();
            return;
        }
        String l = AlarmRinger.label;
        labelView.setText((l != null ? l + "\n" : "")
                + (AlarmRinger.fromPhone ? "Alarma del teléfono" : "Alarma (local)"));
        snooze.setVisibility(AlarmRinger.canSnooze ? View.VISIBLE : View.GONE);
    }
}
