package shminterop.shmbridge.watch;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Incoming call screen from the phone (full-screen intent of {@link CallMirror}).
 * Answer/Reject send {@code call/answer} / {@code call/reject}; the audio stays on the
 * phone unless there is HFP. It closes itself when the call stops ringing.
 * Fixed portrait orientation (manifest): on this watch rotation must stay locked.
 */
public class CallActivity extends Activity {

    private TextView nameView;

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

        TextView head = new TextView(this);
        head.setGravity(Gravity.CENTER);
        head.setTextSize(13f);
        head.setTextColor(Color.LTGRAY);
        head.setText("Llamada en el teléfono");
        root.addView(head);

        nameView = new TextView(this);
        nameView.setGravity(Gravity.CENTER);
        nameView.setTextSize(20f);
        nameView.setTextColor(Color.WHITE);
        root.addView(nameView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        Button reject = new Button(this);
        reject.setText("Rechazar");
        reject.setTextColor(Color.WHITE);
        reject.setBackgroundColor(0xFFC62828);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act(BridgeActionReceiver.ACTION_CALL_REJECT); }
        });
        row.addView(reject);

        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(pad / 2, 1));

        Button answer = new Button(this);
        answer.setText("Contestar");
        answer.setTextColor(Color.WHITE);
        answer.setBackgroundColor(0xFF2E7D32);
        answer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { act(BridgeActionReceiver.ACTION_CALL_ANSWER); }
        });
        row.addView(answer);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad;
        root.addView(row, lp);
        setContentView(root);
    }

    private void act(String action) {
        sendBroadcast(new Intent(action).setClass(this, BridgeActionReceiver.class));
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CallMirror.setUi(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        CallMirror.clearUi(this);
        super.onDestroy();
    }

    void onCallStateChanged() {
        runOnUiThread(new Runnable() {
            @Override public void run() { refresh(); }
        });
    }

    private void refresh() {
        if (!CallMirror.ringing) {
            finish();
            return;
        }
        String n = CallMirror.callerName;
        nameView.setText(n != null ? n : "Llamada entrante");
    }
}
