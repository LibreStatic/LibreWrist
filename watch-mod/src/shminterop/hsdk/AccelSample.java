package shminterop.hsdk;

/**
 * One ACCELEROMETER sample of an {@code onDataUpdated} delivery (protocol 4.5): the three raw
 * axis counts of the sensor hub, with the wall-clock time of the sample.
 */
public final class AccelSample {
    /** Epoch time (ms) of the sample; the PPG_GREEN sample of the same hub event carries the same value. */
    public final long timestampMs;
    /** Raw axis counts, not scaled by the service. */
    public final int x;
    public final int y;
    public final int z;

    public AccelSample(long timestampMs, int x, int y, int z) {
        this.timestampMs = timestampMs;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
