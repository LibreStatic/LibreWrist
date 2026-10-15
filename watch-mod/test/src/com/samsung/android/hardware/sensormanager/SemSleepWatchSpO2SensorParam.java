package com.samsung.android.hardware.sensormanager;

/**
 * Host-test double of the vendor enums of the sleep SpO2 sensor (run-tests.sh only, never
 * dexed). The adapter looks them up by class and constant name through reflection, so the names
 * here are the contract.
 */
public class SemSleepWatchSpO2SensorParam {
    public enum DataType { NORMAL, EMPTY, VERSION }

    public enum VersionType { CALCULATION_ALGO_VERSION, SIGNALCHECK_ALGO_VERSION }

    public enum OperationMode { HM_SPO2_VERSION_1, HM_SPO2_VERSION_2 }
}
