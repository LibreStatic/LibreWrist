package shminterop.ecg;

import java.util.ArrayList;
import java.util.List;

/** Host tests for LeadDebouncer: short no-contact blips are ignored, a real lift still cancels. */
public final class LeadDebouncerTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private static String run(LeadDebouncer d, int... leads) {
        StringBuilder sb = new StringBuilder();
        for (int l : leads) sb.append(d.next(l));
        return sb.toString();
    }

    public static void main(String[] args) {
        List<String> logs = new ArrayList<String>();
        LeadDebouncer d = new LeadDebouncer(logs::add);

        check(run(d, 5, 5, 5).equals("555"), "no contact before the first touch is forwarded as is");
        check(logs.isEmpty(), "no log while nothing changes");

        check(run(d, 0).equals("0"), "contact is forwarded at once");
        check(logs.size() == 1 && logs.get(0).equals("lead: contact"), "contact is logged once");

        logs.clear();
        check(run(d, 0, 5, 0, 0).equals("0000"), "a 1-batch blip is ignored");
        check(run(d, 5, 5, 5, 0).equals("0000"), "a 3-batch blip is ignored");
        check(logs.size() == 2 && logs.get(1).equals("lead: ignored a 3-batch no-contact blip"),
                "ignored blips are logged with their length");

        logs.clear();
        check(run(d, 5, 5, 5, 5, 5).equals("00055"), "4 batches of no contact cancel, then stay off");
        check(logs.size() == 1 && logs.get(0).equals("lead: no contact (4 batches)"), "the lift is logged once");

        check(run(d, 0, 5).equals("00"), "contact after a lift is forwarded at once and debounced again");

        d.reset();
        check(run(d, 5).equals("5"), "reset returns to the no-contact state");

        if (failures > 0) {
            System.out.println(failures + " failure(s)");
            System.exit(1);
        }
    }
}
