package com.samsung.android.hardware.sensormanager;

/** Host-test double of the vendor attribute class (run-tests.sh only): just the Cmd enum. */
public class SemSleepWatchSpO2SensorAttribute extends SemSensorAttribute {
    public enum Cmd { FLUSH, GET_DATA, GET_VERSION, SET_OPERATION_MODE }
}
