package shminterop.spo2;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads an Android system property through the {@code getprop} command (no hidden APIs).
 * Returns "" when the property is unset or the command is unavailable. Tests replace
 * {@link #source}.
 */
public final class SysProp {
    public interface Source {
        String get(String key);
    }

    static volatile Source source = new Source() {
        @Override public String get(String key) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[] {"getprop", key});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                try {
                    String line = r.readLine();
                    return line == null ? "" : line.trim();
                } finally {
                    r.close();
                }
            } catch (Throwable t) {
                return "";
            } finally {
                if (p != null) p.destroy();
            }
        }
    };

    private SysProp() {}

    public static String get(String key) {
        try {
            String v = source.get(key);
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }
}
