package shminterop.ihrn;

/**
 * Turns paired Health Tracking samples into the per-sample fields of SHM's IHRN events and
 * decides the flags. Stateful (previous sample, motion window): one instance per session, one
 * thread. Nothing is invented or smoothed: a sample that cannot be trusted is passed on marked
 * as compromised (agc), which makes SHM's native analysis ask for a new window.
 *
 * Design and open questions: docs/ihrn-adapter.md.
 */
final class IhrnConverter {
    /** Sample period of the IHRN native analysis (25 Hz). */
    static final long PERIOD_MS = 40L;
    /**
     * A timestamp gap above this many periods (120 ms) means dropped samples. On the SM-R920 the
     * hub timestamps jitter between 16 and 74 ms around the 40 ms period, so 2 periods would
     * flag normal jitter. Only the sample timestamps count: a late burst (the hub can stall for
     * seconds with the screen off and then deliver 300 samples at once) is not a gap.
     */
    static final double GAP_FACTOR = 3.0;

    /**
     * led_green = GREEN_SIGN * green + GREEN_OFFSET. Identity: the device probe (SM-R920, on
     * wrist) showed the PPG_GREEN value already in the native range (2.07M..2.41M, positive,
     * inside the [25000, 4106923] window the native keeps; values outside are masked as
     * saturated there).
     */
    static final int GREEN_SIGN = 1;
    static final long GREEN_OFFSET = 0L;

    /**
     * Relative change between two consecutive valid green samples above which the step is
     * treated as a gain change rather than signal. A pulse changes consecutive 40 ms samples by
     * a few percent at most, so 25 % only fires on a real discontinuity; a false positive costs
     * one resampled window, a missed one lets a gain step into the analysis.
     */
    static final double LEVEL_STEP_RATIO = 0.25;

    /** Trailing samples for the motion estimate (1 s at 25 Hz). */
    static final int MOTION_WINDOW = 25;
    /** Fewer samples than this give no motion estimate (motion = false). */
    static final int MOTION_MIN_SAMPLES = 5;
    /**
     * Standard deviation of the accelerometer magnitude over the window above which motion is
     * set. Measured on the SM-R920 at 4096 counts per g: a still wrist gives about 50..200 counts
     * over 1 s, normal arm movement 250..1500. Motion only makes SHM skip the window.
     */
    static final double MOTION_STD_COUNTS = 300.0;

    /** Green status of the service for a usable sample. */
    static final int STATUS_NORMAL = 0;

    private boolean havePrev;
    private long prevTs;
    private boolean haveGood;
    private int lastGoodGreen;
    private final double[] mag = new double[MOTION_WINDOW];
    private int magCount;
    private int magPos;

    void reset() {
        havePrev = false;
        haveGood = false;
        magCount = 0;
        magPos = 0;
    }

    IhrnSample convert(PairedSample p) {
        boolean gap = havePrev && (p.tsMs - prevTs) > GAP_FACTOR * PERIOD_MS;
        boolean statusOk = p.greenStatus == STATUS_NORMAL;
        boolean levelStep = false;
        if (statusOk) {
            if (haveGood) {
                double base = Math.max(Math.abs((double) lastGoodGreen), 1.0);
                levelStep = Math.abs((double) p.green - lastGoodGreen) / base > LEVEL_STEP_RATIO;
            }
            lastGoodGreen = p.green;
            haveGood = true;
        }
        havePrev = true;
        prevTs = p.tsMs;

        double m = Math.sqrt((double) p.accX * p.accX + (double) p.accY * p.accY
                + (double) p.accZ * p.accZ);
        mag[magPos] = m;
        magPos = (magPos + 1) % MOTION_WINDOW;
        if (magCount < MOTION_WINDOW) magCount++;
        boolean motion = magCount >= MOTION_MIN_SAMPLES && std(mag, magCount) > MOTION_STD_COUNTS;

        boolean agc = gap || p.pairingFailed || levelStep || !statusOk;
        return new IhrnSample(p.tsMs, GREEN_SIGN * (long) p.green + GREEN_OFFSET,
                p.accX, p.accY, p.accZ, agc, statusOk, motion, p.green, m, gap);
    }

    /** Std of the first [n] entries (the ring is full from index 0 once n == length). */
    static double std(double[] v, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += v[i];
        double mean = sum / n;
        double sq = 0;
        for (int i = 0; i < n; i++) sq += (v[i] - mean) * (v[i] - mean);
        return Math.sqrt(sq / n);
    }
}
