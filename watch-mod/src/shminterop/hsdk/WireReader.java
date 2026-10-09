package shminterop.hsdk;

/**
 * Minimal read side of a binder parcel. The device implementation wraps
 * {@code android.os.Parcel} ({@link ParcelWire}); host tests drive the pure-Java protocol logic
 * with a fake, because the android.jar methods only throw "Stub!" on a JVM.
 */
public interface WireReader {
    int readInt();
    long readLong();
    float readFloat();
    /** Null is returned for the length -1 marker. */
    String readString();
    /** Current read position in bytes. */
    int dataPosition();
    void setDataPosition(int position);
    /** Bytes left after the current position. */
    int dataAvail();
}
