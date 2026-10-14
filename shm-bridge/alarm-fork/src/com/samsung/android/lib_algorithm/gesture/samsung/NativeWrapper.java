package com.samsung.android.lib_algorithm.gesture.samsung;

/**
 * Our clean-room JNI binding for the public device library {@code lib_algorithm.gesture.samsung.so}.
 * Its name and shape are dictated by the symbols the library exports
 * ({@code Java_com_samsung_android_lib_1algorithm_gesture_samsung_NativeWrapper_*}) and by the
 * fields it reads and writes through JNI. It contains no vendor code.
 */
public class NativeWrapper {
    public int detGesture;
    public double[] prob;
    public boolean status;

    public NativeWrapper() {}

    public native void nativeReset(int mode, int modelType, boolean block);

    public native NativeWrapper nativeRun(int mode, float[] values, int orientMode, int modelType);
}
