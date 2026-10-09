package shminterop.hsdk;

/** Minimal write side of a binder parcel; see {@link WireReader}. */
public interface WireWriter {
    void writeInterfaceToken(String descriptor);
    void writeInt(int value);
    /** Null is written as the length -1 marker. */
    void writeString(String value);
    /** The argument is an {@code android.os.IBinder} on the device; opaque to the protocol logic. */
    void writeStrongBinder(Object binder);
}
