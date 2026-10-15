package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/** Host-test double of the vendor sleep SpO2 event (run-tests.sh only, never dexed). */
public class SemSleepWatchSpO2SensorEvent extends SemSensorEvent {
    private final int sensorId;
    private final Bundle bundle;

    public SemSleepWatchSpO2SensorEvent(int sensorId, Bundle bundle) {
        this.sensorId = sensorId;
        this.bundle = bundle;
    }

    public int getType() {
        return sensorId;
    }

    public Bundle getBundle() {
        return bundle;
    }
}
