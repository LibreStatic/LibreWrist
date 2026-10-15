package shminterop.spo2;

import java.util.Random;

/**
 * Deterministic synthetic night of per-second SpO2 values for end-to-end testing without sleeping
 * (simulation mode of SpO2Adapter, system property debug.shminterop.spo2_sim). Pure Java.
 *
 * Baseline 96..97 with small noise; {@code eventsPerHour} desaturation events per hour, each a
 * drop of 4..8 points over 15..25 s followed by a recovery over 10..15 s. Values are clamped to
 * 70..100. The same arguments always give the same night (fixed seed).
 */
public final class SyntheticNight {
    public static final long SEED = 49L;
    public static final int MAX_HOURS = 24;
    public static final int DEFAULT_EVENTS_PER_HOUR = 10;

    private SyntheticNight() {}

    /**
     * Parses {@code <hours>[:<eventsPerHour>]}.
     * @return {hours, eventsPerHour}, or null when the text is empty or malformed
     */
    public static int[] parseSpec(String spec) {
        if (spec == null) return null;
        String s = spec.trim();
        if (s.isEmpty()) return null;
        try {
            int colon = s.indexOf(':');
            int hours = Integer.parseInt(colon < 0 ? s : s.substring(0, colon).trim());
            int events = colon < 0 ? DEFAULT_EVENTS_PER_HOUR : Integer.parseInt(s.substring(colon + 1).trim());
            if (hours < 1 || hours > MAX_HOURS || events < 0 || events > 120) return null;
            return new int[] {hours, events};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One value per second, {@code hours * 3600} entries. */
    public static int[] generate(int hours, int eventsPerHour) {
        int total = hours * 3600;
        Random rnd = new Random(SEED);
        double[] drop = new double[total];
        for (int h = 0; h < hours; h++) {
            for (int e = 0; e < eventsPerHour; e++) {
                double slot = 3600.0 / eventsPerHour;
                int centre = (int) (h * 3600 + (e + 0.5) * slot + (rnd.nextDouble() - 0.5) * slot * 0.4);
                double depth = 4 + rnd.nextDouble() * 4;
                int down = 15 + rnd.nextInt(11);
                int up = 10 + rnd.nextInt(6);
                for (int t = 0; t < down + up; t++) {
                    int at = centre + t;
                    if (at < 0 || at >= total) continue;
                    double f = t < down ? (double) (t + 1) / down : 1.0 - (double) (t - down + 1) / up;
                    drop[at] = Math.max(drop[at], depth * f);
                }
            }
        }
        int[] out = new int[total];
        double drift = 0;
        for (int i = 0; i < total; i++) {
            drift += (rnd.nextDouble() - 0.5) * 0.05;
            drift = Math.max(-0.5, Math.min(0.5, drift));
            double base = 96.5 + drift + (rnd.nextDouble() - 0.5) * 1.2;
            int v = (int) Math.round(base - drop[i]);
            out[i] = Math.max(70, Math.min(100, v));
        }
        return out;
    }
}
