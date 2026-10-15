package shminterop.spo2;

/**
 * Streaming per-second SpO2 estimator over raw red/IR PPG (pure Java, no Android types).
 *
 * Implements the windowed ratio-of-ratios of docs/spo2-calibration.md 2.1 / docs/spo2-adapter.md:
 *
 *   DC  = trailing mean of the last round(1.5 * fs) samples of each channel;
 *   AC  = (x - DC), then a trailing moving average of 9 samples;
 *   at every whole-second boundary, over the trailing 8 s window:
 *     R = (std(AC_red) / mean(red)) / (std(AC_ir) / mean(ir))
 *   quality gates (a failed gate yields 0): every sample of the window has both channels normal,
 *   Pearson(AC_red, AC_ir) >= 0.7, IR perfusion 100 * std(AC_ir) / mean(ir) within [0.02, 20],
 *   R within [0.08, 1.84];
 *   SpO2 = (int) clamp(109.6912003 - 20.3647995 R - 2.1715099812 R^2, 70, 100).
 *
 * It reproduces the reference function {@code per_second} of the validation script; the only
 * deliberate difference is the cadence. The reference emits every round(fs) samples; this
 * streaming version emits when the wall-clock second of the sample timestamps changes, so the
 * output is bucketed by real seconds whatever the delivery jitter. The window and filter lengths
 * are counted in samples from the nominal rate {@code fs} (100 Hz, the Health Tracking Service
 * rate): a true rate within a couple of percent only stretches the 8 s window slightly, and R
 * is a ratio of two channels filtered identically, so it is insensitive to that.
 *
 * Warm-up: nothing is emitted until the window is full (and the DC filter has settled, whichever
 * is later). A gap of more than 2 s between samples (or a backwards jump) emits the pending
 * second and resets the filters.
 */
public final class Spo2Estimator {
    public static final double C0 = 109.6912003, C1 = -20.3647995, C2 = -2.1715099812;
    public static final double R_MIN = 0.08, R_MAX = 1.84;
    public static final double DC_SECONDS = 1.5;
    public static final int SMOOTH = 9;
    public static final int WINDOW_SECONDS = 8;
    public static final double MIN_CORRELATION = 0.7;
    public static final double PI_MIN = 0.02, PI_MAX = 20.0;
    public static final int WARMUP_SECONDS = 2;
    public static final int NOMINAL_FS = 100;
    public static final long GAP_MS = 2000L;

    /** Receives one result per whole second. */
    public interface Output {
        /**
         * @param secondEpochMs start of the second (multiple of 1000)
         * @param value 0 when invalid, else 70..100
         * @param r the ratio of ratios, NaN when it could not be computed (window not ok / flat)
         */
        void onSecond(long secondEpochMs, int value, double r);
    }

    private final Output out;
    private final int dcn;
    private final int win;
    private final int start;

    // Ring buffers of the trailing window (index = sample number % win).
    private final int[] rawRed, rawIr;
    private final double[] acRed, acIr;
    private final boolean[] okRing;
    private final double[] smoothRed = new double[SMOOTH];
    private final double[] smoothIr = new double[SMOOTH];

    private int n;                 // samples since the last reset
    private long dcSumRed, dcSumIr;
    private boolean have;
    private long lastTs;
    private long curSec;

    public Spo2Estimator(Output out) {
        this(NOMINAL_FS, out);
    }

    public Spo2Estimator(int fs, Output out) {
        this.out = out;
        this.dcn = Math.max(1, (int) Math.round(DC_SECONDS * fs));
        this.win = WINDOW_SECONDS * fs;
        this.start = Math.max(win, WARMUP_SECONDS * fs + dcn);
        rawRed = new int[win];
        rawIr = new int[win];
        acRed = new double[win];
        acIr = new double[win];
        okRing = new boolean[win];
    }

    public static int fromR(double r) {
        double v = C0 + C1 * r + C2 * r * r;
        return (int) Math.min(100.0, Math.max(70.0, v));
    }

    /** Drops all filter state (the pending second is not emitted). */
    public void reset() {
        n = 0;
        dcSumRed = 0;
        dcSumIr = 0;
        have = false;
    }

    /** Feeds one sample; ok = both channels reported normal by the service. */
    public void add(long tsMs, int red, int ir, boolean ok) {
        long sec = Math.floorDiv(tsMs, 1000L);
        if (have) {
            long d = tsMs - lastTs;
            if (d > GAP_MS || d < -GAP_MS) {
                finishSecond();
                reset();
            } else if (sec > curSec) {
                finishSecond();
            }
        }
        int idx = n % win;
        rawRed[idx] = red;
        rawIr[idx] = ir;
        okRing[idx] = ok;
        dcSumRed += red;
        dcSumIr += ir;
        if (n >= dcn) {
            int old = (n - dcn) % win;
            dcSumRed -= rawRed[old];
            dcSumIr -= rawIr[old];
        }
        int dn = Math.min(n + 1, dcn);
        smoothRed[n % SMOOTH] = red - (double) dcSumRed / dn;
        smoothIr[n % SMOOTH] = ir - (double) dcSumIr / dn;
        int sn = Math.min(n + 1, SMOOTH);
        double sr = 0, si = 0;
        for (int k = 0; k < sn; k++) {
            sr += smoothRed[k];
            si += smoothIr[k];
        }
        acRed[idx] = sr / sn;
        acIr[idx] = si / sn;
        n++;
        have = true;
        lastTs = tsMs;
        if (n == 1 || sec > curSec) curSec = sec;
    }

    /** Emits the second that just ended, from the trailing window. */
    private void finishSecond() {
        if (n < start) return;
        long when = curSec * 1000L;
        int first = n - win;
        double sumR = 0, sumI = 0, rawR = 0, rawI = 0;
        for (int j = first; j < n; j++) {
            int i = j % win;
            if (!okRing[i]) {
                out.onSecond(when, 0, Double.NaN);
                return;
            }
            sumR += acRed[i];
            sumI += acIr[i];
            rawR += rawRed[i];
            rawI += rawIr[i];
        }
        double mr = sumR / win, mi = sumI / win;
        double vr = 0, vi = 0, cov = 0;
        for (int j = first; j < n; j++) {
            int i = j % win;
            double a = acRed[i] - mr, b = acIr[i] - mi;
            vr += a * a;
            vi += b * b;
            cov += a * b;
        }
        vr /= win;
        vi /= win;
        cov /= win;
        if (vr <= 0 || vi <= 0) {
            out.onSecond(when, 0, Double.NaN);
            return;
        }
        double corr = cov / Math.sqrt(vr * vi);
        double dr = rawR / win, di = rawI / win;
        if (dr <= 0 || di <= 0) {
            out.onSecond(when, 0, Double.NaN);
            return;
        }
        double pi = 100 * Math.sqrt(vi) / di;
        double r = (Math.sqrt(vr) / dr) / (Math.sqrt(vi) / di);
        if (corr < MIN_CORRELATION || pi < PI_MIN || pi > PI_MAX || r < R_MIN || r > R_MAX) {
            out.onSecond(when, 0, r);
        } else {
            out.onSecond(when, fromR(r), r);
        }
    }
}
