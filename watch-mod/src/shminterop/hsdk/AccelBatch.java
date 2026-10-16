package shminterop.hsdk;

/** One {@code onDataUpdated} delivery of an ACCELEROMETER tracker (protocol 4.5). */
public final class AccelBatch {
    public final AccelSample[] samples;

    public AccelBatch(AccelSample[] samples) {
        this.samples = samples;
    }
}
