package shminterop.hsdk;

/**
 * One {@code onDataUpdated} delivery. Lead-off, sequence and thresholds are per batch and come only
 * from data point index 0 (protocol 4.2); {@link #ABSENT} (-1) means the service did not provide them.
 */
public final class EcgBatch {
    public static final int ABSENT = -1;
    /** Lead-off value meaning "no electrode contact"; 0 means contact. */
    public static final int LEAD_OFF = 5;

    public final EcgSample[] samples;
    public final int leadOff;
    public final int sequence;
    /** NaN when absent. */
    public final float maxThresholdMv;
    public final float minThresholdMv;

    public EcgBatch(EcgSample[] samples, int leadOff, int sequence,
                    float maxThresholdMv, float minThresholdMv) {
        this.samples = samples;
        this.leadOff = leadOff;
        this.sequence = sequence;
        this.maxThresholdMv = maxThresholdMv;
        this.minThresholdMv = minThresholdMv;
    }
}
