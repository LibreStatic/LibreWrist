package shminterop.shmbridge.watch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Captures the text of SHM's result screens (package filtered in the XML config)
 * so the measurement (BP/ECG) can be read from the bridge and sent to the phone.
 */
public class ShmCaptureService extends AccessibilityService {

    private static volatile String lastCapture = "";
    private static volatile long lastTs = 0;
    private static long lastEventTs = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastEventTs < 1500) return; // throttle
        lastEventTs = now;
        CharSequence cls = event.getClassName();
        StringBuilder sb = new StringBuilder();
        sb.append("screen=").append(cls == null ? "?" : cls.toString().substring(cls.toString().lastIndexOf('.') + 1));
        try {
            AccessibilityNodeInfo root = null;
            // look for SHM's window among the active windows (the "active window"
            // is usually the launcher's AOD overlay on Wear)
            if (getWindows() != null) {
                android.util.Log.d("SHMBridge", "windows=" + getWindows().size());
                for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        android.util.Log.d("SHMBridge", "win pkg=" + r.getPackageName()
                                + " active=" + w.isActive() + " focused=" + w.isFocused());
                        if (MainActivity.SHM_PKG.contentEquals(r.getPackageName())) {
                            root = r;
                            break;
                        }
                    }
                }
            } else {
                android.util.Log.d("SHMBridge", "windows=null (flag no aplicado)");
            }
            if (root == null) root = getRootInActiveWindow();
            if (root != null) {
                Deque<AccessibilityNodeInfo> dq = new ArrayDeque<>();
                dq.add(root);
                int n = 0;
                while (!dq.isEmpty() && n < 200) {
                    AccessibilityNodeInfo node = dq.poll();
                    CharSequence t = node.getText();
                    CharSequence d = node.getContentDescription();
                    if (t != null && t.length() > 0) {
                        sb.append(" | ").append(t);
                        n++;
                    } else if (d != null && d.length() > 0) {
                        sb.append(" | [").append(d).append(']');
                        n++;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo c = node.getChild(i);
                        if (c != null) dq.add(c);
                    }
                }
                android.util.Log.d("SHMBridge", "capture nodes=" + n + " total=" + sb.length()
                        + " pkg=" + root.getPackageName());
            } else {
                android.util.Log.d("SHMBridge", "capture root null");
                sb.append(" | (root null)");
            }
        } catch (Throwable ignored) {}
        String text = sb.toString();
        if (text.length() > "screen=".length() + 1) {
            lastCapture = text;
            lastTs = now;
        }
    }

    @Override
    public void onInterrupt() {}

    public static String getLastCapture() {
        if (lastCapture.isEmpty()) return "";
        return lastCapture + " (@" + lastTs + ")";
    }
}
