package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

/**
 * Host-test double of the vendor IHRN event (run-tests.sh only, never dexed): Bundle-backed with
 * the getters and keys of SHM 1.5.1.022 (qm0.onEventChanged), so a drift of a key or its type in
 * IhrnAdapter fails a test.
 */
public class SemPpgIhrnSensorEvent extends SemSensorEvent {
    private final Bundle b;

    public SemPpgIhrnSensorEvent(int sensorId, Bundle bundle) {
        this.b = bundle;
    }

    public int[] getAccXArray() { return b.getIntArray("acc_x"); }
    public int[] getAccYArray() { return b.getIntArray("acc_y"); }
    public int[] getAccZArray() { return b.getIntArray("acc_z"); }
    public SemPpgIhrnSensorParam.Accuracy getAccuracy() {
        return (SemPpgIhrnSensorParam.Accuracy) b.getSerializable("accuracy");
    }
    public boolean[] getAgcList() { return b.getBooleanArray("agc"); }
    public int getCount() { return b.getInt("count"); }
    public int getDataSequence() { return b.getInt("data_sequence"); }
    public boolean[] getEcgModeList() { return b.getBooleanArray("ecg_mode"); }
    public SemPpgIhrnSensorParam.EventType getEventType() {
        return (SemPpgIhrnSensorParam.EventType) b.getSerializable("event_type");
    }
    public long[] getLedGreenList() { return b.getLongArray("led_green"); }
    public int[] getModeList() { return b.getIntArray("mode"); }
    public boolean[] getMotionList() { return b.getBooleanArray("motion"); }
    public long getSystick() { return b.getLong("systick"); }
    public long[] getSystickList() { return b.getLongArray("systick_array"); }
    public long getTimestamp() { return b.getLong("timestamp"); }
    public boolean[] getWristList() { return b.getBooleanArray("wrist"); }
}
