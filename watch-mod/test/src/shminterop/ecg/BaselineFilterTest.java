package shminterop.ecg;

/** Host tests for BaselineFilter: offset, drift and step removal, and the QRS left as it is. */
public final class BaselineFilterTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static final int FS = BaselineFilter.FS;

    /** A 20 ms triangular QRS of 1 mV every 400 samples (75 bpm). */
    private static double qrs(int i) {
        int k = i % 400;
        return k < 10 ? (k < 5 ? k / 5.0 : (10 - k) / 5.0) : 0;
    }

    public static void main(String[] args) {
        BaselineFilter f = new BaselineFilter();
        double worst = 0;
        for (int i = 0; i < 2 * FS; i++) worst = Math.max(worst, Math.abs(f.next(180.0)));
        check(worst < 1e-9, "a constant baseline gives 0 from the first sample");

        // 1 mV/s drift on top of 180 mV: no offset is left once the filter has settled.
        f.reset();
        double last = 0;
        worst = 0;
        for (int i = 0; i < 10 * FS; i++) {
            last = f.next(180.0 + i / (double) FS);
            if (i >= 3 * FS) worst = Math.max(worst, Math.abs(last));
        }
        System.out.println("  ramp residue after 3 s: " + worst + " mV");
        check(worst < 0.01, "a 1 mV/s drift leaves less than 10 uV");

        // A 3 mV jump (finger pressure) is gone 2 s later.
        f.reset();
        for (int i = 0; i < 2 * FS; i++) f.next(160.0);
        worst = 0;
        for (int i = 0; i < 4 * FS; i++) {
            last = f.next(163.0);
            if (i >= 2 * FS) worst = Math.max(worst, Math.abs(last));
        }
        System.out.println("  step residue after 2 s: " + worst + " mV");
        check(worst < 0.03, "a 3 mV step leaves less than 30 uV after 2 s");

        // Beats on a drifting baseline: R keeps its height, the baseline between beats stays near 0.
        f.reset();
        double rMin = Double.MAX_VALUE, rMax = 0, baseWorst = 0, peak = -Double.MAX_VALUE;
        for (int i = 0; i < 12 * FS; i++) {
            double y = f.next(-140.0 + 0.8 * i / FS + qrs(i));
            if (i < 4 * FS) continue;
            int k = i % 400;
            if (k == 0) peak = -Double.MAX_VALUE;
            if (k < 10) peak = Math.max(peak, y);
            if (k == 10) { rMin = Math.min(rMin, peak); rMax = Math.max(rMax, peak); }
            if (k >= 200) baseWorst = Math.max(baseWorst, Math.abs(y));
        }
        System.out.println("  R " + rMin + ".." + rMax + " mV, baseline " + baseWorst + " mV");
        check(rMin > 0.95 && rMax < 1.01, "a 1 mV QRS keeps at least 95% of its height");
        check(baseWorst < 0.03, "the baseline between beats stays within 30 uV");

        // reset() takes the baseline again from the next sample (contact after a rail value).
        f.reset();
        for (int i = 0; i < FS; i++) f.next(-745.0);
        f.reset();
        check(Math.abs(f.next(170.0)) < 1e-9, "after reset the first sample is the new baseline");

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
    }
}
