package shminterop.shmbridge.phone;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SHM Bridge — phone app. Client of the WearOS companion.
 * Sends commands (ECG/BP/SYNC) to the watch and shows the data it returns.
 */
public class MainActivity extends Activity {

    private EditText host;
    private EditText token;
    private EditText watchMac;
    private TextView log;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);

        // /__bridge__/ communications channel (notifications, alarms, calls): always.
        try {
            startForegroundService(new Intent(this, CommsService.class));
        } catch (Exception e) {
            addLog("Canal comms err: " + e);
        }
        // BT server auto-start: off by default (pref bt_autostart). The channel RFCOMM is
        // served by BridgeLink (CommsService); this activity must not open BtServer on
        // its own.
        if (prefs.getBoolean("bt_autostart", false)) {
            try {
                startForegroundService(new Intent(this, BtServer.class));
            } catch (Exception e) {
                addLog("BT server autostart err: " + e);
            }
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        host = field("IP del reloj (ej: 10.0.0.2)");
        host.setText(prefs.getString("host", ""));
        root.addView(host);

        token = field("Token (lo muestra el reloj)");
        token.setText(prefs.getString("token", ""));
        root.addView(token);

        watchMac = field("MAC BT del reloj (para emparejar, ej: AA:BB:CC:DD:EE:FF)");
        watchMac.setText(prefs.getString("watch_mac", ""));
        root.addView(watchMac);

        root.addView(button("CONECTAR / PING", new Runnable() {
            @Override public void run() { session("PING"); }
        }));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(buttonHalf("ECG", new Runnable() {
            @Override public void run() { session("ECG"); }
        }));
        row.addView(buttonHalf("BP MEDIR", new Runnable() {
            @Override public void run() { session("BPRUN"); }
        }));
        root.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(buttonHalf("BP MENÚ", new Runnable() {
            @Override public void run() { session("BP"); }
        }));
        row2.addView(buttonHalf("SHM HOME", new Runnable() {
            @Override public void run() { session("MAIN"); }
        }));
        root.addView(row2);

        root.addView(button("SINCRONIZAR DATOS", new Runnable() {
            @Override public void run() { sync(); }
        }));

        root.addView(button("ÚLTIMO RESULTADO (pantalla reloj)", new Runnable() {
            @Override public void run() { last(); }
        }));

        android.widget.LinearLayout rowVendorTest = new android.widget.LinearLayout(this);
        rowVendorTest.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        rowVendorTest.addView(buttonHalf("WCS VENDOR TEST", new Runnable() {
            @Override public void run() {
                shminterop.relay.VendorReplyTrigger.sendFromBridge(MainActivity.this);
                addLog("WCS vendor test enviado (feature_exchange samsung)");
            }
        }));
        rowVendorTest.addView(buttonHalf("RELAY SHM ON", new Runnable() {
            @Override public void run() { startRelayBt(); }
        }));
        root.addView(rowVendorTest);

        root.addView(button("BT START (servidor RFCOMM)", new Runnable() {
            @Override public void run() {
                save();
                try {
                    startForegroundService(new Intent(MainActivity.this, BtServer.class));
                    addLog("BT server: iniciando (FGS)");
                } catch (Exception e) {
                    addLog("BT server err: " + e);
                }
            }
        }));

        final Button auto = button("", null);
        auto.setText("BT AUTOSTART: " + (prefs.getBoolean("bt_autostart", false) ? "SÍ" : "NO"));
        auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = !prefs.getBoolean("bt_autostart", false);
                prefs.edit().putBoolean("bt_autostart", on).apply();
                auto.setText("BT AUTOSTART: " + (on ? "SÍ" : "NO"));
            }
        });
        root.addView(auto);

        root.addView(button("CANAL COMMS (estado)", new Runnable() {
            @Override public void run() {
                try {
                    startForegroundService(new Intent(MainActivity.this, CommsService.class));
                } catch (Exception e) {
                    addLog("Canal comms err: " + e);
                }
                addLog("Canal comms: " + CommsHub.get(MainActivity.this).status());
            }
        }));

        root.addView(button("BT PAIR (reloj)", new Runnable() {
            @Override public void run() { pairWatch(); }
        }));

        root.addView(button("BT PING", new Runnable() {
            @Override public void run() { btCommand("PING"); }
        }));

        root.addView(button("BT ECG", new Runnable() {
            @Override public void run() { btCommand("ECG"); }
        }));

        root.addView(button("BT SYNC", new Runnable() {
            @Override public void run() { btCommand("SYNC"); }
        }));

        root.addView(button("BT LAST", new Runnable() {
            @Override public void run() { btCommand("LAST"); }
        }));

        log = new TextView(this);
        log.setTextSize(13f);
        log.setPadding(0, pad, 0, pad);
        ScrollView sc = new ScrollView(this);
        sc.addView(log);
        root.addView(sc, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String label, final Runnable cmd) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run(cmd); }
        });
        return b;
    }

    private Button buttonHalf(String label, final Runnable cmd) {
        Button b = button(label, cmd);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private void run(final Runnable r) { pool.execute(r); }

    private void save() {
        prefs.edit().putString("host", host.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .putString("watch_mac", watchMac.getText().toString().trim()).apply();
    }

    /** Relay over BT: BtRelayServer joins RFCOMM(watch) with 8774(SHM). */
    private void startRelayBt() {
        try {
            startForegroundService(new Intent(this, BtRelayServer.class));
            startForegroundService(new Intent(this, RelayKeepAliveService.class));
            addLog("Relay BT ON: RFCOMM(watch) <-> 8774(SHM)");
        } catch (Exception e) {
            addLog("Relay BT err: " + e);
        }
    }

    private void startRelay() {
        save();
        String hh = host.getText().toString().trim();
        if (hh.isEmpty()) hh = prefs.getString("host", "");
        if (hh.isEmpty()) { addLog("Falta la IP del reloj"); return; }
        final String h = hh;
        // FGS: keeps the cached app freezer from freezing the process in the background
        // (frozen = relay sockets aborted and connects hanging).
        try {
            startForegroundService(new Intent(this, RelayKeepAliveService.class));
        } catch (Exception e) {
            addLog("FGS: " + e);
        }
        // Dedicated thread: a single-thread pool does not work (a live connection would block
        // the next accept). One thread per incoming SHM connection.
        Thread loop = new Thread(new Runnable() { @Override public void run() {
            addLog("Relay ON: " + h + ":8773 <-> local:8774");
            java.net.ServerSocket local = null;
            try {
                local = new java.net.ServerSocket(8774);
            } catch (Exception e) {
                addLog("Relay: 8774 ocupado: " + e);
                return;
            }
            while (true) {
                try {
                    final Socket sh = local.accept();
                    sh.setKeepAlive(true);
                    addLog("Relay: SHM conectado, enlazando watch");
                    new Thread(new Runnable() { @Override public void run() {
                        Socket remote = null;
                        try {
                            android.util.Log.i("SHMBridgePhone", "Link: connecting to " + h + ":8773");
                            remote = new Socket();
                            remote.connect(new InetSocketAddress(h, 8773), 5000);
                            remote.setKeepAlive(true);
                            addLog("Relay: watch enlazado");
                            Thread a = new Thread(new Pump(remote.getInputStream(), sh.getOutputStream(), "watch->shm"));
                            Thread b = new Thread(new Pump(sh.getInputStream(), remote.getOutputStream(), "shm->watch"));
                            a.start(); b.start();
                            a.join(); b.join();
                            addLog("Relay: sesion cerrada; esperando SHM");
                        } catch (Throwable e) {
                            addLog("Relay err: " + e);
                        } finally {
                            try { sh.close(); } catch (Exception ignored) {}
                            try { if (remote != null) remote.close(); } catch (Exception ignored) {}
                        }
                    }}, "relay-link").start();
                } catch (Throwable e) {
                    addLog("Relay accept err: " + e);
                    try { Thread.sleep(3000); } catch (InterruptedException ig) { return; }
                }
            }
        }}, "relay-loop");
        loop.setDaemon(true);
        loop.start();
    }


    /** Triggers BT pairing towards the watch (shows the system dialog). */
    private void pairWatch() {
        save();
        final String mac = watchMac.getText().toString().trim().toUpperCase();
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            addLog("MAC BT del reloj inválida o vacía");
            return;
        }
        run(new Runnable() { @Override public void run() {
            try {
                android.bluetooth.BluetoothManager bm =
                        (android.bluetooth.BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                android.bluetooth.BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    addLog("BT apagado");
                    return;
                }
                android.bluetooth.BluetoothDevice dev = adapter.getRemoteDevice(mac);
                int state = dev.getBondState();
                addLog("Watch bond state: " + state);
                if (state == android.bluetooth.BluetoothDevice.BOND_BONDED) {
                    addLog("Ya emparejado");
                    return;
                }
                // Receiver to log the bond result.
                android.content.IntentFilter f = new android.content.IntentFilter(
                        android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                registerReceiver(new android.content.BroadcastReceiver() {
                    @Override public void onReceive(android.content.Context ctx, android.content.Intent i) {
                        int ns = i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1);
                        int ps = i.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1);
                        addLog("Bond: " + ps + " -> " + ns);
                    }
                }, f);
                boolean ok = dev.createBond();
                addLog("createBond: " + ok + " (confirmá el diálogo del sistema)");
            } catch (Exception e) {
                addLog("pair err: " + e);
            }
        }});
    }

    /** Sends a command over the active BT session; if there is none, reports it. */
    private void btCommand(final String cmd) {
        run(new Runnable() { @Override public void run() {
            BtProtoClient.Session s = BtProtoClient.active;
            if (s == null || !s.isAlive()) {
                addLog("BT: sin sesión activa (¿el reloj se conectó?)");
                return;
            }
            long timeout = cmd.equals("SYNC") ? 20000 : 15000;
            String resp = s.command(cmd, timeout);
            if (resp == null) {
                addLog("BT: sin respuesta (sesión muerta o timeout)");
                return;
            }
            if (resp.startsWith("DATA ")) {
                String payload = resp.substring(5);
                if (cmd.equals("SYNC")) {
                    render(payload);
                } else if (cmd.equals("LAST")) {
                    addLog("Captura BT:" + payload.replace(" | ", "\n  "));
                } else {
                    addLog("BT resp: " + payload);
                }
            } else {
                addLog("BT " + cmd + ": " + resp);
            }
        }});
    }

    static class Pump implements Runnable {
        private final java.io.InputStream in;
        private final java.io.OutputStream out;
        private final String tag;
        Pump(java.io.InputStream in, java.io.OutputStream out, String tag) {
            this.in = in; this.out = out; this.tag = tag;
        }
        @Override public void run() {
            byte[] buf = new byte[16384];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                }
                android.util.Log.i("SHMBridgePhone", "Pump " + tag + ": EOF");
            } catch (Throwable e) {
                android.util.Log.i("SHMBridgePhone", "Pump " + tag + ": " + e);
            } finally {
                try { out.close(); } catch (Exception ignored2) {}
            }
        }
    }

    private interface WithConn { void run(BufferedReader in, PrintWriter out) throws Exception; }

    private void session(final String cmd) {
        run(new Runnable() { @Override public void run() { doSession(cmd, null, false); } });
    }

    private void sync() {
        run(new Runnable() { @Override public void run() {
            doSession("SYNC", new WithConn() {
                @Override public void run(BufferedReader in, PrintWriter out) throws Exception {
                    out.println("SYNC");
                    String resp = in.readLine();
                    if (resp != null && resp.startsWith("DATA ")) {
                        render(resp.substring(5));
                    }
                }
            }, true);
        }});
    }

    private void last() {
        run(new Runnable() { @Override public void run() {
            doSession("LAST", new WithConn() {
                @Override public void run(BufferedReader in, PrintWriter out) throws Exception {
                    out.println("LAST");
                    String resp = in.readLine();
                    if (resp != null && resp.startsWith("DATA ")) {
                        addLog("Captura:\n" + resp.substring(5).replace(" | ", "\n  "));
                    }
                }
            }, true);
        }});
    }

    private void doSession(String cmd, WithConn extra, boolean keepOpen) {
        save();
        String h = host.getText().toString().trim();
        String t = token.getText().toString().trim();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(h, 8771), 6000);
            s.setSoTimeout(30000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            String banner = in.readLine(); // SHMBRIDGE 1
            out.println("AUTH " + t);
            String resp = in.readLine();
            if (!"OK".equals(resp)) { addLog("Auth falló: " + resp); return; }
            addLog("Conectado (" + banner + ")");
            if (!keepOpen) {
                out.println(cmd);
                addLog(h + " <- " + cmd + ": " + in.readLine());
            } else {
                extra.run(in, out);
                addLog("SYNC listo");
            }
        } catch (Exception e) {
            addLog("ERR: " + e.getMessage());
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void render(final String json) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject o = new JSONObject(json);
            String[] keys = {"blood_pressure", "heart_rate", "spo2", "resting_hr", "hrv",
                    "sleep", "steps", "skin_temp"};
            for (String k : keys) {
                if (!o.has(k)) continue;
                Object v = o.get(k);
                sb.append("== ").append(k).append("\n");
                if (v instanceof JSONObject) { // {"error":..,"partial":..}
                    JSONObject e = (JSONObject) v;
                    sb.append("  error: ").append(e.optString("error")).append("\n");
                    JSONArray part = e.optJSONArray("partial");
                    if (part != null) appendArr(sb, part);
                } else if (v instanceof JSONArray) {
                    appendArr(sb, (JSONArray) v);
                }
            }
        } catch (Exception e) {
            sb.append("JSON inválido: ").append(e.getMessage());
        }
        final String text = sb.toString();
        runOnUiThread(new Runnable() { @Override public void run() { log.setText(text); } });
    }

    private void appendArr(StringBuilder sb, JSONArray arr) {
        int n = Math.min(arr.length(), 10);
        for (int i = 0; i < n; i++) {
            Object item = arr.opt(i);
            sb.append("  ").append(item instanceof JSONObject ? item.toString() : String.valueOf(item));
            sb.append("\n");
        }
        if (arr.length() > n) sb.append("  ... (").append(arr.length() - n).append(" más)\n");
    }

    private void addLog(final String s) {
        android.util.Log.i("SHMBridgePhone", s);
        runOnUiThread(new Runnable() { @Override public void run() {
            if (log != null) log.setText(s + "\n" + log.getText());
        }});
    }
}
