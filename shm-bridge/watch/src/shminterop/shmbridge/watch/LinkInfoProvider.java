package shminterop.shmbridge.watch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * Read-only link facts for the installer, which reads them over adb:
 * {@code adb shell content query --uri content://shminterop.shmbridge.watch.link}.
 * {@code phone_mac} is the Bluetooth address of the device that served the last bridge session
 * (empty until the first link); the installer associates it in the Companion Device Manager
 * ({@link CompanionLink}). {@code companion} is 1 when the link runs in companion mode.
 * Only the shell and the app itself may read it.
 */
public class LinkInfoProvider extends ContentProvider {

    static final String[] COLUMNS = {"phone_mac", "companion", "status"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SHELL_UID && uid != Process.myUid()) {
            throw new SecurityException("bridge link info: caller not allowed");
        }
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[] {
                RfcommLink.phoneMac(getContext()),
                RfcommLink.companion ? 1 : 0,
                RfcommLink.status});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("read-only"); }
}
