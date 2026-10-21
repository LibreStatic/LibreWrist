package shminterop.ecg;

/**
 * Diagnostic beat detector on the adapter's filtered ECG (logging only; it never changes what
 * SHM receives).
 *
 * Reports, per stats window, the beat-to-beat heart rate it sees and the sign of each beat's
 * main deflection. Comparing that with the heart rate SHM shows during a measurement tells a
 * noisy or inverted signal apart from a counting problem on the SHM side.
 *
 * Detection is sign-agnostic: a beat is an upward crossing of |x - baseline| over half of a
 * slowly decaying envelope (floor 0.1 mV), with a 250 ms refractory period. Samples are
 * assumed to arrive at 500 Hz (measured, docs/ecg-calibration.md §5a).
 *
 * Pure Java (no Android types) so it can be tested on the host. Not thread-safe: the adapter
 * calls it under its delivery lock.
 */
final class BeatMonitor {

    static final int FS = 500;
    static final int REFRACTORY = FS / 4;
    static final double MIN_THRESHOLD_MV = 0.1;
    static final double BASE_ALPHA = 0.005;   // ~0.4 s baseline, removes the high-pass residue
    static final double ENV_DECAY = 0.9986;   // envelope halves in ~1 s
    static final int MAX_LISTED = 12;

    private double base = Double.NaN;
    private double env = 0;
    private boolean above = false;
    private long n = 0;
    private long lastBeat = Long.MIN_VALUE;

    // per window
    private final StringBuilder hrList = new StringBuilder();
    private int listed = 0;
    private int beats = 0, posBeats = 0, negBeats = 0;
    private double hrMin = Double.MAX_VALUE, hrMax = 0;
    private double posPeak = 0, negPeak = 0;

    /** Starts over (new measurement, or contact lost). */
    void reset() {
        base = Double.NaN;
        env = 0;
        above = false;
        n = 0;
        lastBeat = Long.MIN_VALUE;
        clearWindow();
    }

    /** One filtered sample in mV, with contact. */
    void feed(double mv) {
        if (Double.isNaN(base)) base = mv;
        base += BASE_ALPHA * (mv - base);
        double x = mv - base;
        if (x > posPeak) posPeak = x;
        if (x < negPeak) negPeak = x;
        double ax = Math.abs(x);
        env = Math.max(ax, env * ENV_DECAY);
        double thr = Math.max(MIN_THRESHOLD_MV, env * 0.5);
        boolean now = ax > thr;
        if (now && !above && (lastBeat == Long.MIN_VALUE || n - lastBeat >= REFRACTORY)) {
            if (x > 0) posBeats++; else negBeats++;
            if (lastBeat != Long.MIN_VALUE) {
                double hr = 60.0 * FS / (n - lastBeat);
                beats++;
                if (hr < hrMin) hrMin = hr;
                if (hr > hrMax) hrMax = hr;
                if (listed < MAX_LISTED) {
                    if (listed > 0) hrList.append(',');
                    hrList.append(Math.round(hr));
                    listed++;
                }
            }
            lastBeat = n;
        }
        above = now;
        n++;
    }

    /** The window's summary line (null when no beat interval was seen), then a new window. */
    String summary() {
        String s = null;
        if (beats > 0) {
            s = String.format(java.util.Locale.US,
                    "beats: hr=[%s%s] min=%d max=%d polarity=+%d/-%d peak=+%.3f/%.3f mV",
                    hrList, beats > listed ? ",..." : "", Math.round(hrMin), Math.round(hrMax),
                    posBeats, negBeats, posPeak, negPeak);
        } else if (posBeats + negBeats > 0 || posPeak > 0 || negPeak < 0) {
            s = String.format(java.util.Locale.US, "beats: none timed peak=+%.3f/%.3f mV", posPeak, negPeak);
        }
        clearWindow();
        return s;
    }

    private void clearWindow() {
        hrList.setLength(0);
        listed = 0;
        beats = posBeats = negBeats = 0;
        hrMin = Double.MAX_VALUE;
        hrMax = 0;
        posPeak = negPeak = 0;
    }
}
