package shminterop.ecg;

/** Host tests for BeatMonitor: rate and polarity on synthetic beats, noise rejection. */
public final class BeatMonitorTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static long t;

    /** A 20 ms triangular QRS of the given amplitude every period samples, plus a small offset. */
    private static void beats(BeatMonitor m, int seconds, int period, double amp, double offset) {
        int total = seconds * BeatMonitor.FS;
        for (int i = 0; i < total; i++, t++) {
            int k = (int) (t % period);
            double v = offset;
            if (k < 10) v += amp * (k < 5 ? k / 5.0 : (10 - k) / 5.0);
            m.feed(v);
        }
    }

    public static void main(String[] args) {
        BeatMonitor m = new BeatMonitor();

        beats(m, 2, 400, 1.0, 0);          // 75 bpm, upright
        m.summary();                        // drop the warm-up window
        beats(m, 4, 400, 1.0, 0);
        String s = m.summary();
        System.out.println("  " + s);
        check(s != null && s.contains("min=75 max=75"), "upright 75 bpm is timed at 75");
        check(s != null && s.contains("polarity=+5/-0"), "upright beats count as positive");

        m.reset();
        beats(m, 2, 400, -1.5, -0.4);      // 75 bpm, inverted, with a DC offset
        m.summary();
        beats(m, 4, 400, -1.5, -0.4);
        s = m.summary();
        System.out.println("  " + s);
        check(s != null && s.contains("min=75 max=75"), "inverted 75 bpm with offset is timed at 75");
        check(s != null && s.contains("polarity=+0/-5"), "inverted beats count as negative");

        m.reset();
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < 2 * BeatMonitor.FS; i++) m.feed(r.nextGaussian() * 0.01);
        s = m.summary();
        check(s == null || !s.contains("hr="), "10 uV noise produces no beats");

        m.reset();
        check(m.summary() == null, "an empty window has no summary");

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
    }
}
