package com.samsung.android.hardware.sensormanager;

/**
 * Host-test double of the vendor IHRN enums (run-tests.sh only). The adapter resolves them by
 * class and constant name through reflection, so the names here are the contract.
 */
public class SemPpgIhrnSensorParam {
    public enum EventType { NORMAL, SYSTICK }

    public enum Accuracy { ERROR, NORMAL }

    public enum BatchMode { ONE, MEDIUM, MAX }
}
