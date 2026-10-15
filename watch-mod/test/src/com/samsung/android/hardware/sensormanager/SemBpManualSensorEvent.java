package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/**
 * Host-test double of the vendor BP event (run-tests.sh only, never dexed). Unlike the
 * signatures-only stubs in watch-mod/stubs this one is behavioural: it keeps the bundle and
 * getGreenRaw reads the "green_raw" key, the contract of the BP case in the patched SHM consumer
 * (pl.smali: check-cast + getGreenRaw, no other key is read). getBundle exposes the bundle for
 * direct assertions, so the test catches a drift of the key BpAdapter writes.
 */
public class SemBpManualSensorEvent extends SemSensorEvent {
    private final Bundle bundle;

    public SemBpManualSensorEvent(int sensorId, Bundle bundle) {
        this.bundle = bundle;
    }

    public int getGreenRaw() {
        return bundle.getInt("green_raw");
    }

    public Bundle getBundle() {
        return bundle;
    }
}
