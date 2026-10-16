package shminterop.ihrn;

import java.util.Arrays;
import java.util.Locale;

/**
 * Collects the debug numbers of one 250-sample window and returns one log line when it is full,
 * so a device run can check the conversion (green range, accelerometer scale, real rate, gaps)
 * without a line per sample. One thread only.
 */
final class IhrnWindowLog {
    static final int WINDOW = 250;

    private final long[] green = new long[WINDOW];
    private final double[] acc = new double[WINDOW];
    private int n;
    private int gaps;
    private int compromised;
    private int notWrist;
    private long firstTs;
    private long lastTs;

    /** Returns the line when this sample completes a window, else null. */
    String add(IhrnSample s) {
        if (n == 0) firstTs = s.tsMs;
        lastTs = s.tsMs;
        green[n] = s.rawGreen;
        acc[n] = s.accMagnitude;
        n++;
        if (s.gapBefore) gaps++;
        if (s.agc) compromised++;
        if (!s.wrist) notWrist++;
        if (n < WINDOW) return null;
        long[] g = green.clone();
        double[] a = acc.clone();
        Arrays.sort(g);
        Arrays.sort(a);
        double span = lastTs - firstTs;
        double rate = span > 0 ? (WINDOW - 1) * 1000.0 / span : 0.0;
        String line = String.format(Locale.US,
                "window: n=%d green min/med/max=%d/%d/%d |a| median=%.0f counts rate=%.1f Hz"
                        + " gaps=%d compromised=%d notWrist=%d",
                WINDOW, g[0], g[WINDOW / 2], g[WINDOW - 1], a[WINDOW / 2], rate, gaps, compromised,
                notWrist);
        n = 0;
        gaps = 0;
        compromised = 0;
        notWrist = 0;
        return line;
    }

    void reset() {
        n = 0;
        gaps = 0;
        compromised = 0;
        notWrist = 0;
    }
}
