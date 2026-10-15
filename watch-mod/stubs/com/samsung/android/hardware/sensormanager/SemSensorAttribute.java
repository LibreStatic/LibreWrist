package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/** Signatures only (never dexed): the attribute container passed to SemSensorManager.setAttribute. */
public class SemSensorAttribute {
    public SemSensorAttribute() {}
    public Bundle getAttribute(int sensorType) { return null; }
    public void setAttribute(int sensorType, Bundle bundle) {}
}
