package shminterop.ecg;

/**
 * Removes the electrode baseline from the DC-coupled ECG the health service delivers.
 *
 * The signal sits on a baseline of roughly +/-200 mV that keeps drifting during a measurement
 * (0.3 to 1 mV/s measured) and jumps when the finger pressure changes, while SHM expects a
 * centred signal within its validity band. A second-order Butterworth high-pass at
 * {@link #CUTOFF_HZ} has no steady-state error on a ramp and settles within about 1 s after a
 * step; the single-pole filter used before (time constant 2 s) left an offset of
 * drift rate x 2 s that moved with the drift and took about 8 s to settle after contact.
 *
 * Pure Java (no Android types) so it can be tested on the host. Not thread-safe: the adapter
 * calls it under its delivery lock.
 */
final class BaselineFilter {

    static final int FS = 500;
    static final double CUTOFF_HZ = 0.5;

    private final double b0, b1, b2, a1, a2;
    private double x1, x2, y1, y2;
    private boolean seeded = false;

    BaselineFilter() {
        this(CUTOFF_HZ, FS);
    }

    BaselineFilter(double cutoffHz, double fs) {
        double k = Math.tan(Math.PI * cutoffHz / fs);
        double norm = 1.0 / (1.0 + Math.sqrt(2.0) * k + k * k);
        b0 = norm;
        b1 = -2.0 * norm;
        b2 = norm;
        a1 = 2.0 * (k * k - 1.0) * norm;
        a2 = (1.0 - Math.sqrt(2.0) * k + k * k) * norm;
    }

    /** Forgets the baseline; the next sample becomes the new one (new measurement, new contact). */
    void reset() {
        seeded = false;
    }

    /** Takes one sample in mV and returns it without the baseline. */
    double next(double mv) {
        if (!seeded) {
            // As if the input had always been at this level: the output starts at 0.
            x1 = x2 = mv;
            y1 = y2 = 0;
            seeded = true;
        }
        double y = b0 * mv + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = mv;
        y2 = y1;
        y1 = y;
        return y;
    }
}
