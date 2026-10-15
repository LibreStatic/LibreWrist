package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/** Host-test double of the vendor attribute container (run-tests.sh only, never dexed). */
public class SemSensorAttribute {
    private Bundle bundle;

    public Bundle getAttribute(int sensorType) {
        return bundle;
    }

    public void setAttribute(int sensorType, Bundle b) {
        bundle = b;
    }
}
