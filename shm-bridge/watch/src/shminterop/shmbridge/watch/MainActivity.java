package shminterop.shmbridge.watch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SHM Bridge — WearOS companion.
 * Takes advantage of the fact that stock SHM (com.samsung.android.shealthmonitors) exposes its
 * ECG/BP measurement activities exported and without permission: they are launched directly with startActivity,
 * without going through the app's home or its gates (finding F2).
 */
public class MainActivity extends Activity {

    static final String SHM_PKG = "com.samsung.android.shealthmonitors";
    static final String ACT_MAIN = "com.samsung.android.shealthmonitors.ui.activity.MainActivity";
    static final String ACT_ECG = "com.samsung.android.shealthmonitors.ecg.ui.activity.EcgMainActivity";
    static final String ACT_BP = "com.samsung.android.shealthmonitors.bp.ui.activity.BpMainActivity";
    static final String ACT_BP_RUN = "com.samsung.android.shealthmonitors.bp.ui.activity.BpMeasureRunActivity";

    private TextView status;
    private TextView info;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setText("SHM Bridge");
        root.addView(status);

        final TextView bt = new TextView(this);
        bt.setGravity(Gravity.CENTER);
        bt.setTextSize(11f);
        root.addView(bt);
        // Simple refresh of the BT state every 2 s while the activity is visible.
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                bt.setText("BT: " + BtClient.status);
                h.postDelayed(this, 2000);
            }
        };
        h.post(refresh);

        info = new TextView(this);
        info.setGravity(Gravity.CENTER);
        info.setTextSize(12f);
        root.addView(info);

        root.addView(button("INICIAR BRIDGE", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, BridgeService.class);
                startForegroundService(i);
                status.setText("Bridge activo");
                info.setText("IP: " + BridgeService.localIp() + "\nPuerto: " + BridgeService.PORT
                        + "\nToken: " + BridgeService.token(MainActivity.this));
            }
        }));

        root.addView(button("ECG", new View.OnClickListener() {
            @Override public void onClick(View v) { launch(ACT_ECG); }
        }));

        root.addView(button("BP (medir)", new View.OnClickListener() {
            @Override public void onClick(View v) { launch(ACT_BP_RUN); }
        }));

        root.addView(button("BP (menú)", new View.OnClickListener() {
            @Override public void onClick(View v) { launch(ACT_BP); }
        }));

        root.addView(button("SHM (home)", new View.OnClickListener() {
            @Override public void onClick(View v) { launch(ACT_MAIN); }
        }));

        setContentView(root);
        // auto-start the bridge if it is not running yet
        Intent i = new Intent(this, BridgeService.class);
        startForegroundService(i);
        status.setText("Bridge activo");
        info.setText("IP: " + BridgeService.localIp() + "\nPuerto: " + BridgeService.PORT
                + "\nToken: " + BridgeService.token(this));
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        b.setLayoutParams(lp);
        return b;
    }

    private void launch(String cls) {
        try {
            Intent i = new Intent();
            i.setClassName(SHM_PKG, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            status.setText("Lanzado:\n" + cls.substring(cls.lastIndexOf('.') + 1));
        } catch (Exception e) {
            status.setText("ERR: " + e.getMessage());
        }
    }
}
