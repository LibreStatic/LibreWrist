package shminterop.hsdk;

/**
 * One PPG sample of an {@code onDataUpdated} delivery (protocol 4.4): the green, red and IR LED ADC
 * counts plus the per-sample channel quality of each. Unlike ECG, every field is per data point; the service sends one
 * data point per sensor event.
 */
public final class PpgSample {
    /** Wall-clock time (ms) at which the service received the sensor event. */
    public final long timestampMs;
    /** Green LED ADC count; raw sensor units, the service applies no offset or scaling. */
    public final int greenAdc;
    /** Channel quality: {@link PpgBatch#STATUS_NORMAL} or {@link PpgBatch#STATUS_ERROR}. */
    public final int greenStatus;
    /** Red LED ADC count (position 2); 0 when the channel was not requested or absent. */
    public final int redAdc;
    /** Red channel quality (position 3); {@link PpgBatch#STATUS_ERROR} when absent. */
    public final int redStatus;
    /** IR LED ADC count (position 4); 0 when the channel was not requested or absent. */
    public final int irAdc;
    /** IR channel quality (position 5); {@link PpgBatch#STATUS_ERROR} when absent. */
    public final int irStatus;

    public PpgSample(long timestampMs, int greenAdc, int greenStatus) {
        this(timestampMs, greenAdc, greenStatus, 0, PpgBatch.STATUS_ERROR, 0, PpgBatch.STATUS_ERROR);
    }

    public PpgSample(long timestampMs, int greenAdc, int greenStatus,
                     int redAdc, int redStatus, int irAdc, int irStatus) {
        this.timestampMs = timestampMs;
        this.greenAdc = greenAdc;
        this.greenStatus = greenStatus;
        this.redAdc = redAdc;
        this.redStatus = redStatus;
        this.irAdc = irAdc;
        this.irStatus = irStatus;
    }

    /** True when the service reported usable red-channel quality for this sample. */
    public boolean isRedNormal() {
        return redStatus == PpgBatch.STATUS_NORMAL;
    }

    /** True when the service reported usable IR-channel quality for this sample. */
    public boolean isIrNormal() {
        return irStatus == PpgBatch.STATUS_NORMAL;
    }

    /** True when the service reported usable green-channel quality for this sample. */
    public boolean isGreenNormal() {
        return greenStatus == PpgBatch.STATUS_NORMAL;
    }
}
