package shminterop.ihrn;

/** One green PPG sample joined with the accelerometer sample of the same timestamp. */
final class PairedSample {
    final long tsMs;
    final int green;
    final int greenStatus;
    final int accX, accY, accZ;
    /**
     * True when no accelerometer sample of this timestamp arrived within
     * {@link IhrnPairer#HOLD_MS}: the accelerometer values are then the last ones seen (zeros if
     * there were none) and the sample is reported to SHM as compromised.
     */
    final boolean pairingFailed;

    PairedSample(long tsMs, int green, int greenStatus, int accX, int accY, int accZ,
                 boolean pairingFailed) {
        this.tsMs = tsMs;
        this.green = green;
        this.greenStatus = greenStatus;
        this.accX = accX;
        this.accY = accY;
        this.accZ = accZ;
        this.pairingFailed = pairingFailed;
    }
}
