package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/** Signatures only (never dexed): the BP sensor event the patched SHM pipeline consumes. */
public class SemBpManualSensorEvent extends SemSensorEvent {
    public SemBpManualSensorEvent(int sensorId, Bundle bundle) {}
    public int getGreenRaw() { return 0; }
}
