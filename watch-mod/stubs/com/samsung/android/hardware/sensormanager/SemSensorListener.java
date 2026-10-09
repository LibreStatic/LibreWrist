package com.samsung.android.hardware.sensormanager;

public interface SemSensorListener {
    void onEventChanged(SemSensorEvent event);
    void onStatusChanged(int status, String s);
}
