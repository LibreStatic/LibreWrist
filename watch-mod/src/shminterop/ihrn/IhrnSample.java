package shminterop.ihrn;

/** One sample in SHM's IHRN terms (SemPpgIhrnSensorEvent array element). */
final class IhrnSample {
    final long tsMs;
    final long ledGreen;
    final int accX, accY, accZ;
    /** SHM's "agc" flag; true marks the sample as compromised (see IhrnConverter). */
    final boolean agc;
    final boolean wrist;
    final boolean motion;
    /** Raw green value and accelerometer magnitude, kept for the per-window debug log. */
    final int rawGreen;
    final double accMagnitude;
    /** The green timestamp gap before this sample exceeded {@link IhrnConverter#GAP_FACTOR} periods. */
    final boolean gapBefore;

    IhrnSample(long tsMs, long ledGreen, int accX, int accY, int accZ, boolean agc, boolean wrist,
               boolean motion, int rawGreen, double accMagnitude, boolean gapBefore) {
        this.tsMs = tsMs;
        this.ledGreen = ledGreen;
        this.accX = accX;
        this.accY = accY;
        this.accZ = accZ;
        this.agc = agc;
        this.wrist = wrist;
        this.motion = motion;
        this.rawGreen = rawGreen;
        this.accMagnitude = accMagnitude;
        this.gapBefore = gapBefore;
    }
}
