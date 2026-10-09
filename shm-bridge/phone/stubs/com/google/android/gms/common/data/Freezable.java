package com.google.android.gms.common.data;

/**
 * Compile-time stub (not dexed): Freezable from play-services-base is not in
 * psw.aar (play-services-wearable), but DataItem/DataEvent extend it. At runtime
 * the real class exists on the device. Same rationale as the tasks stubs.
 */
public interface Freezable<T> {
    T freeze();
    boolean isDataValid();
}
