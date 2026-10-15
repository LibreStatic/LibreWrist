package shminterop.spo2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plain main-based tests of Spo2Estimator (run-tests.sh only): synthetic red/IR photoplethysmo-
 * grams at 100 Hz with a chosen perfusion ratio must give the polynomial value of the known R,
 * and every quality gate must reject. Exits non-zero on failure.
 */
public final class Spo2EstimatorTest {
    private static int failures;
    static final long T0 = 1_700_000_000_000L; // multiple of 1000

    public static void main(String[] args) {
        knownRatio();
        perSecondBucketing();
        antiCorrelatedAndUncorrelated();
        badStatusWindow();
        ratioOutOfRange();
        perfusionTooLow();
        gapReset();
        fromRPolynomial();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object expected, Object actual, String what) {
        check(expected == null ? actual == null : expected.equals(actual),
                what + " expected=" + expected + " actual=" + actual);
    }

    static final class Rec implements Spo2Estimator.Output {
        final List<long[]> out = new ArrayList<long[]>(); // {secondMs, value}
        final List<Double> rs = new ArrayList<Double>();

        @Override public void onSecond(long secondEpochMs, int value, double r) {
            out.add(new long[] {secondEpochMs, value});
            rs.add(r);
        }
    }

    /** In-phase 1.2 Hz pulses: perfusion pr on red, pi on IR, both with the given DC levels. */
    static Rec run(int seconds, double dcRed, double dcIr, double pr, double pi, boolean[] okAt) {
        Rec rec = new Rec();
        Spo2Estimator e = new Spo2Estimator(rec);
        for (int i = 0; i < seconds * 100; i++) {
            double s = Math.sin(2 * Math.PI * 1.2 * i / 100.0);
            boolean ok = okAt == null || okAt[i];
            e.add(T0 + i * 10L, (int) Math.round(dcRed * (1 + pr * s)),
                    (int) Math.round(dcIr * (1 + pi * s)), ok);
        }
        return rec;
    }

    static void knownRatio() {
        double[] rs = {0.5, 0.7, 1.0, 1.4};
        for (double r : rs) {
            Rec rec = run(20, 100000, 120000, 0.01 * r, 0.01, null);
            int expected = Spo2Estimator.fromR(r);
            check(rec.out.size() >= 10, "R=" + r + " emits enough seconds: " + rec.out.size());
            for (int i = 0; i < rec.out.size(); i++) {
                long v = rec.out.get(i)[1];
                check(Math.abs(v - expected) <= 1, "R=" + r + " second " + i + " value " + v
                        + " expected ~" + expected);
                check(Math.abs(rec.rs.get(i) - r) < 0.03, "R=" + r + " measured " + rec.rs.get(i));
            }
        }
    }

    static void perSecondBucketing() {
        // 20 s: window (8 s) fills at sample 800, so seconds 7..18 are final when the next
        // second starts; second 19 is still pending at the end.
        Rec rec = run(20, 100000, 120000, 0.007, 0.01, null);
        eq(12, rec.out.size(), "emitted seconds");
        for (int i = 0; i < rec.out.size(); i++) {
            eq(T0 + (7 + i) * 1000L, rec.out.get(i)[0], "second timestamp " + i);
        }
        // Jittered delivery timestamps still bucket by wall-clock second.
        Rec jit = new Rec();
        Spo2Estimator e = new Spo2Estimator(jit);
        Random rnd = new Random(1);
        for (int i = 0; i < 2000; i++) {
            double s = Math.sin(2 * Math.PI * 1.2 * i / 100.0);
            long ts = T0 + i * 10L + 5 + (long) (rnd.nextInt(7) - 3);
            e.add(ts, (int) Math.round(100000 * (1 + 0.007 * s)), (int) Math.round(120000 * (1 + 0.01 * s)), true);
        }
        eq(12, jit.out.size(), "jittered emitted seconds");
        for (int i = 1; i < jit.out.size(); i++) {
            eq(jit.out.get(i - 1)[0] + 1000L, jit.out.get(i)[0], "consecutive seconds " + i);
        }
    }

    static void antiCorrelatedAndUncorrelated() {
        Rec anti = run(20, 100000, 120000, -0.007, 0.01, null);
        check(anti.out.size() > 0, "anti emits");
        for (long[] o : anti.out) eq(0L, o[1], "anti-correlated channels give 0");

        // Different frequencies: correlation over 8 s is ~0.
        Rec rec = new Rec();
        Spo2Estimator e = new Spo2Estimator(rec);
        for (int i = 0; i < 2000; i++) {
            e.add(T0 + i * 10L,
                    (int) Math.round(100000 * (1 + 0.007 * Math.sin(2 * Math.PI * 2.9 * i / 100.0))),
                    (int) Math.round(120000 * (1 + 0.01 * Math.sin(2 * Math.PI * 1.2 * i / 100.0))), true);
        }
        check(rec.out.size() > 0, "uncorrelated emits");
        for (long[] o : rec.out) eq(0L, o[1], "uncorrelated channels give 0");

        // Independent white noise on both channels.
        Random rnd = new Random(7);
        rec = new Rec();
        e = new Spo2Estimator(rec);
        for (int i = 0; i < 2000; i++) {
            e.add(T0 + i * 10L, 100000 + (int) (rnd.nextGaussian() * 300),
                    120000 + (int) (rnd.nextGaussian() * 300), true);
        }
        check(rec.out.size() > 0, "noise emits");
        for (long[] o : rec.out) eq(0L, o[1], "independent noise gives 0");
    }

    static void badStatusWindow() {
        boolean[] ok = new boolean[2000];
        java.util.Arrays.fill(ok, true);
        ok[1000] = false; // first sample of second 10
        Rec rec = run(20, 100000, 120000, 0.007, 0.01, ok);
        for (long[] o : rec.out) {
            long sec = (o[0] - T0) / 1000L;
            if (sec >= 10 && sec <= 17) eq(0L, o[1], "second " + sec + " window holds the bad sample");
            else check(o[1] > 0, "second " + sec + " is clean, value " + o[1]);
        }
    }

    static void ratioOutOfRange() {
        for (double r : new double[] {0.04, 2.5}) {
            Rec rec = run(20, 100000, 120000, 0.01 * r, 0.01, null);
            check(rec.out.size() > 0, "R=" + r + " emits");
            for (long[] o : rec.out) eq(0L, o[1], "R=" + r + " is rejected");
        }
    }

    static void perfusionTooLow() {
        // 0.007 % IR perfusion: under the 0.02 % floor although the ratio itself is fine.
        Rec rec = run(20, 1000000, 1000000, 0.0001, 0.0001, null);
        check(rec.out.size() > 0, "low perfusion emits");
        for (long[] o : rec.out) eq(0L, o[1], "perfusion below the floor is rejected");
    }

    static void gapReset() {
        Rec rec = new Rec();
        Spo2Estimator e = new Spo2Estimator(rec);
        long t = T0;
        for (int i = 0; i < 1200; i++, t += 10) { // 12 s
            double s = Math.sin(2 * Math.PI * 1.2 * i / 100.0);
            e.add(t, (int) Math.round(100000 * (1 + 0.007 * s)), (int) Math.round(120000 * (1 + 0.01 * s)), true);
        }
        int before = rec.out.size();
        eq(4, before, "seconds 7..10 final before the gap");
        t += 5000; // gap > 2 s
        for (int i = 0; i < 300; i++, t += 10) { // 3 s, shorter than the warm-up window
            double s = Math.sin(2 * Math.PI * 1.2 * i / 100.0);
            e.add(t, (int) Math.round(100000 * (1 + 0.007 * s)), (int) Math.round(120000 * (1 + 0.01 * s)), true);
        }
        // The pending second 11 is emitted by the gap; nothing after (filters restarted).
        eq(before + 1, rec.out.size(), "pending second emitted at the gap, none during warm-up");
        eq(T0 + 11_000L, rec.out.get(before)[0], "pending second timestamp");
    }

    static void fromRPolynomial() {
        eq(98, Spo2Estimator.fromR(0.5), "fromR(0.5) truncates 98.96");
        eq(70, Spo2Estimator.fromR(1.84), "fromR(1.84) = 64.8 clamps to 70");
        eq(100, Spo2Estimator.fromR(0.0), "fromR lower R clamps to 100");
    }
}
