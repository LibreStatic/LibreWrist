package shminterop.hsdk;

/**
 * One {@code onDataUpdated} delivery of a PPG tracker (protocol 4.4). The service builds one data
 * point per sample, so unlike {@link EcgBatch} there are no per-batch fields.
 */
public final class PpgBatch {
    /** Green channel produced a usable sample. */
    public static final int STATUS_NORMAL = 0;
    /** No quality guarantee; also the producer default when a channel value is absent. */
    public static final int STATUS_ERROR = -1;

    public final PpgSample[] samples;

    public PpgBatch(PpgSample[] samples) {
        this.samples = samples;
    }
}
