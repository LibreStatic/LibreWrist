package shminterop.hsdk;

import android.os.IBinder;
import android.os.Parcel;

/** {@link Parcel}-backed {@link WireReader}/{@link WireWriter} (device only). */
final class ParcelWire implements WireReader, WireWriter {
    private final Parcel p;

    ParcelWire(Parcel p) {
        this.p = p;
    }

    @Override public int readInt() { return p.readInt(); }
    @Override public long readLong() { return p.readLong(); }
    @Override public float readFloat() { return p.readFloat(); }
    @Override public String readString() { return p.readString(); }
    @Override public int dataPosition() { return p.dataPosition(); }
    @Override public void setDataPosition(int position) { p.setDataPosition(position); }
    @Override public int dataAvail() { return p.dataSize() - p.dataPosition(); }

    @Override public void writeInterfaceToken(String descriptor) { p.writeInterfaceToken(descriptor); }
    @Override public void writeInt(int value) { p.writeInt(value); }
    @Override public void writeString(String value) { p.writeString(value); }
    @Override public void writeStrongBinder(Object binder) { p.writeStrongBinder((IBinder) binder); }
}
