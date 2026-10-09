package shminterop.shmbridge.watch;

import android.content.Context;

import java.io.BufferedReader;
import java.io.PrintWriter;

/**
 * One-line-per-command protocol shared between the TCP transport
 * (BridgeService) and the Bluetooth RFCOMM transport (BtClient).
 * Format:
 *   server -> "SHMBRIDGE 1"
 *   client -> "AUTH <token>"          server -> "OK" | "ERR AUTH"
 *   "PING"  -> "PONG"
 *   "ECG" | "BP" | "BPRUN" | "MAIN" -> "OK <launched>" | "OK-NOTIF <label>" | "ERR <msg>"
 *   "SYNC"  -> "DATA <one-line json>"
 *   "LAST"  -> "DATA <capture>" | "ERR sin captura"
 *   "BYE"   -> closes
 */
public final class ProtoHandler {

    private ProtoHandler() {}

    /** Runs the command loop until the client closes or sends BYE. */
    public static void run(Context ctx, BufferedReader in, PrintWriter out) {
        try {
            out.println("SHMBRIDGE 1");
            String line = in.readLine();
            if (line == null || !line.startsWith("AUTH ")) { return; }
            String tok = line.substring(5).trim();
            if (!constantEq(tok, BridgeService.token(ctx))) { out.println("ERR AUTH"); return; }
            out.println("OK");

            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.equals("BYE")) break;
                try {
                    if (line.equals("PING")) {
                        out.println("PONG");
                    } else if (line.equals("ECG")) {
                        out.println(BridgeService.launch(ctx,
                                "com.samsung.android.shealthmonitor.ecg.ui.activity.EcgMainActivity"));
                    } else if (line.equals("BPRUN")) {
                        out.println(BridgeService.launch(ctx,
                                "com.samsung.android.shealthmonitor.bp.ui.activity.BpMeasureRunActivity"));
                    } else if (line.equals("BP")) {
                        out.println(BridgeService.launch(ctx,
                                "com.samsung.android.shealthmonitor.bp.ui.activity.BpMainActivity"));
                    } else if (line.equals("MAIN")) {
                        out.println(BridgeService.launch(ctx,
                                "com.samsung.android.shealthmonitor.ui.activity.MainActivity"));
                    } else if (line.equals("SYNC")) {
                        String json = HcReader.readAllJson(ctx);
                        out.println("DATA " + json);
                    } else if (line.equals("LAST")) {
                        String cap = ShmCaptureService.getLastCapture();
                        out.println(cap.isEmpty() ? "ERR sin captura (activar accessibility)"
                                : "DATA " + cap);
                    } else {
                        out.println("ERR unknown command");
                    }
                } catch (Exception e) {
                    out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean constantEq(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
