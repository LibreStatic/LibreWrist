package shminterop.hsdk;

/**
 * One PPG sample of an {@code onDataUpdated} delivery (protocol 4.4): the green LED ADC count plus
 * the per-sample channel quality. Unlike ECG, every field is per data point; the service sends one
 * data point per sensor event.
 */
public final class PpgSample {
    /** Wall-clock time (ms) at which the service received the sensor event. */
    public final long timestampMs;
    /** Green LED ADC count; raw sensor units, the service applies no offset or scaling. */
    public final int greenAdc;
    /** Channel quality: {@link PpgBatch#STATUS_NORMAL} or {@link PpgBatch#STATUS_ERROR}. */
    public final int greenStatus;

    public PpgSample(long timestampMs, int greenAdc, int greenStatus) {
        this.timestampMs = timestampMs;
        this.greenAdc = greenAdc;
        this.greenStatus = greenStatus;
    }

    /** True when the service reported usable green-channel quality for this sample. */
    public boolean isGreenNormal() {
        return greenStatus == PpgBatch.STATUS_NORMAL;
    }
}
