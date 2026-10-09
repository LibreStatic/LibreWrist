package shminterop.hsdk;

/** The service sent a parcel that does not match the documented layout. */
public final class WireFormatException extends RuntimeException {
    public WireFormatException(String message) {
        super(message);
    }
}
