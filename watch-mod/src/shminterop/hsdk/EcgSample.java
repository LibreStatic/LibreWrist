package shminterop.hsdk;

/** One ECG sample. {@code mv} is {@link Float#NaN} when the service reports it as unavailable (-9999.0). */
public final class EcgSample {
    /** Wall-clock time (ms) at which the service received the sensor event; shared by the whole batch. */
    public final long timestampMs;
    public final float mv;

    public EcgSample(long timestampMs, float mv) {
        this.timestampMs = timestampMs;
        this.mv = mv;
    }

    public boolean hasMv() {
        return !Float.isNaN(mv);
    }
}
