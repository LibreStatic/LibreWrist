package com.samsung.android.hardware.sensormanager;

/** Host-test double of the vendor listener interface (run-tests.sh only, never dexed). */
public interface SemSensorListener {
    void onEventChanged(SemSensorEvent event);
    void onStatusChanged(int status, String s);
}
