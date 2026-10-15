package shminterop.spo2;

import java.util.Arrays;

/** Plain main-based tests of SyntheticNight (run-tests.sh only). Exits non-zero on failure. */
public final class SyntheticNightTest {
    private static int failures;

    public static void main(String[] args) {
        spec();
        shape();
        deterministic();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void spec() {
        check(Arrays.equals(new int[] {5, 20}, SyntheticNight.parseSpec("5:20")), "5:20");
        check(Arrays.equals(new int[] {3, SyntheticNight.DEFAULT_EVENTS_PER_HOUR}, SyntheticNight.parseSpec("3")), "3");
        check(Arrays.equals(new int[] {2, 0}, SyntheticNight.parseSpec(" 2 : 0 ")), "2:0 with blanks");
        check(SyntheticNight.parseSpec("") == null, "empty");
        check(SyntheticNight.parseSpec(null) == null, "null");
        check(SyntheticNight.parseSpec("x") == null, "garbage");
        check(SyntheticNight.parseSpec("0:5") == null, "zero hours");
        check(SyntheticNight.parseSpec("25") == null, "too many hours");
        check(SyntheticNight.parseSpec("5:-1") == null, "negative events");
    }

    static void shape() {
        int[] v = SyntheticNight.generate(5, 20);
        check(v.length == 5 * 3600, "length " + v.length);
        int min = 100, max = 0, below90 = 0;
        for (int x : v) {
            min = Math.min(min, x);
            max = Math.max(max, x);
            if (x < 92) below90++;
        }
        check(min >= 70 && max <= 100, "range " + min + ".." + max);
        check(max >= 96, "baseline reaches 96: " + max);
        check(min <= 92 && min >= 86, "deepest drop 4..8 below ~96.5: " + min);
        check(below90 > 0, "desaturations present");

        // Count events: dips below 93 separated by more than 5 s (noise can flicker at the edge).
        int runs = 0, lastBelow = -100;
        for (int i = 0; i < v.length; i++) {
            if (v[i] <= 93) {
                if (i - lastBelow > 5) runs++;
                lastBelow = i;
            }
        }
        check(runs >= 80 && runs <= 100, "event count for 5 h x 20/h: " + runs);

        int[] none = SyntheticNight.generate(1, 0);
        int nmin = 100;
        for (int x : none) nmin = Math.min(nmin, x);
        check(nmin >= 94, "no events -> stays near baseline: " + nmin);
    }

    static void deterministic() {
        check(Arrays.equals(SyntheticNight.generate(2, 10), SyntheticNight.generate(2, 10)), "same arguments, same night");
        check(!Arrays.equals(SyntheticNight.generate(2, 10), SyntheticNight.generate(2, 11)), "different events differ");
    }
}
