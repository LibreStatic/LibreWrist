package shminterop.spo2;

/** Plain main-based tests of the ApneaTestDriver value-change logic (run-tests.sh only). */
public final class ApneaTestDriverTest {
    private static int failures;

    public static void main(String[] args) {
        mapping();
        changes();
        eventTimes();
        inertWithoutProperty();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void eq(Object expected, Object actual, String what) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            failures++;
            System.out.println("FAIL: " + what + " expected=" + expected + " actual=" + actual);
        }
    }

    static void mapping() {
        eq(0, ApneaTestDriver.stateFor("sleep"), "sleep -> SLEEPING(0)");
        eq(1, ApneaTestDriver.stateFor("wake"), "wake -> AWAKE(1)");
        eq(-1, ApneaTestDriver.stateFor("nap"), "unknown ignored");
        eq(-1, ApneaTestDriver.stateFor(""), "empty ignored");
        eq(-1, ApneaTestDriver.stateFor("SLEEP"), "case sensitive");
    }

    static void changes() {
        ApneaTestDriver.Tracker t = new ApneaTestDriver.Tracker();
        eq(-1, t.onValue(""), "initial empty");
        eq(0, t.onValue("sleep"), "sleep");
        eq(-1, t.onValue("sleep"), "same value is not a change");
        eq(1, t.onValue("wake"), "wake");
        eq(-1, t.onValue("bogus"), "unknown value");
        eq(-1, t.onValue("bogus"), "unknown value again");
        eq(-1, t.onValue(""), "cleared");
        eq(0, t.onValue(" sleep "), "trimmed value after a clear counts as a change");
        eq(-1, t.onValue(null), "null treated as empty");

        // The first value read at start counts as a change.
        ApneaTestDriver.Tracker first = new ApneaTestDriver.Tracker();
        eq(0, first.onValue("sleep"), "initial value acted on");
    }

    static void eventTimes() {
        long now = 1_792_103_867_642L;
        eq(now, ApneaTestDriver.eventTime(0, now, ""), "SLEEPING without simulation: now");
        eq(now - 5 * 3600_000L, ApneaTestDriver.eventTime(0, now, "5:20"), "SLEEPING backdated by the night");
        eq(now - 3 * 3600_000L, ApneaTestDriver.eventTime(0, now, "3"), "hours-only spec");
        eq(now, ApneaTestDriver.eventTime(1, now, "5:20"), "AWAKE always now");
        eq(now, ApneaTestDriver.eventTime(0, now, "junk"), "malformed spec: now");
    }

    static void inertWithoutProperty() {
        SysProp.source = new SysProp.Source() {
            @Override public String get(String key) { return ""; }
        };
        ApneaTestDriver.ensureStarted();
        boolean thread = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("ApneaTestDriver".equals(t.getName())) thread = true;
        }
        eq(false, thread, "no thread when the property is empty");
        ApneaTestDriver.ensureStarted(); // idempotent
    }
}
