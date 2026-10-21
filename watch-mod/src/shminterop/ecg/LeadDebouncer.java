package shminterop.ecg;

/**
 * Debounces the per-batch lead-off flag before it reaches the SHM lead state machine.
 *
 * Once SHM has accepted contact (50 consecutive lead=0 events, LEAD_ON_CHECKED), a single
 * lead=5 event cancels the measurement (LEAD_OFF). The HTS lead flag sometimes reports one
 * isolated "no contact" batch right after the finger settles on the key, which aborted
 * otherwise good measurements. A 0 -> 5 transition is therefore forwarded only after
 * {@link #OFF_BATCHES} consecutive lead=5 batches (about 80 ms at 10 samples per batch,
 * 500 Hz); a real finger lift still cancels. 5 -> 0 is forwarded at once, so the contact check
 * itself is unchanged.
 *
 * Pure Java (no Android types) so it can be tested on the host. Not thread-safe: the adapter
 * calls it under its delivery lock.
 */
final class LeadDebouncer {

    static final int CONTACT = 0;
    static final int NO_CONTACT = 5;
    static final int OFF_BATCHES = 4;

    /** Receives one line per lead transition (forwarded or ignored), never one per batch. */
    interface Log {
        void log(String s);
    }

    private final Log log;
    private int forwarded = NO_CONTACT;
    private int offRun = 0;

    LeadDebouncer(Log log) {
        this.log = log;
    }

    /** Back to the start state (no contact), e.g. when a new measurement starts. */
    void reset() {
        forwarded = NO_CONTACT;
        offRun = 0;
    }

    /** Takes the normalized lead of one batch (0 or 5) and returns the lead to forward. */
    int next(int lead) {
        if (lead == NO_CONTACT) {
            if (forwarded == NO_CONTACT) return NO_CONTACT;
            offRun++;
            if (offRun < OFF_BATCHES) return CONTACT;
            forwarded = NO_CONTACT;
            log.log("lead: no contact (" + offRun + " batches)");
            offRun = 0;
            return NO_CONTACT;
        }
        if (offRun > 0) {
            log.log("lead: ignored a " + offRun + "-batch no-contact blip");
            offRun = 0;
        }
        if (forwarded != CONTACT) {
            forwarded = CONTACT;
            log.log("lead: contact");
        }
        return CONTACT;
    }
}
