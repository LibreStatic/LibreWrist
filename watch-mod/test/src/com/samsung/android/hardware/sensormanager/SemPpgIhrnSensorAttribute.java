package com.samsung.android.hardware.sensormanager;

import android.os.Bundle;

import java.util.EnumSet;

/** Host-test double of the vendor IHRN attribute (run-tests.sh only): same bundle as setBatchMode. */
public class SemPpgIhrnSensorAttribute extends SemSensorAttribute {
    public enum Cmd { BATCH_MODE, SYSTICK }

    private final EnumSet<Cmd> cmd = EnumSet.noneOf(Cmd.class);
    private Bundle bundle = new Bundle();

    public boolean setBatchMode(SemPpgIhrnSensorParam.BatchMode mode) {
        cmd.add(Cmd.BATCH_MODE);
        bundle.putSerializable("cmd", cmd);
        bundle.putSerializable("batch_mode", mode);
        setAttribute(0x25, bundle);
        return true;
    }
}
