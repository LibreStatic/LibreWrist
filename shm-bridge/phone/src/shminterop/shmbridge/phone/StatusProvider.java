package shminterop.shmbridge.phone;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only status of the bridge for the patcher app and other signature-level callers
 * (authority {@code shminterop.shmbridge.phone.status}, read permission
 * {@code shminterop.shmbridge.permission.STATUS}, declared in the manifest).
 *
 * <p>A single row with the columns of {@link #COLUMNS}. It has no side effects: it never creates
 * the {@link CommsHub} nor starts the link or the service; without a hub it reports zeros and
 * empty strings. Nothing sensitive is exposed (state flags, capability names, an age, the
 * version).</p>
 */
public class StatusProvider extends ContentProvider {

    static final String AUTHORITY = "shminterop.shmbridge.phone.status";
    static final String TYPE = "vnd.android.cursor.item/vnd.shminterop.shmbridge.status";
    static final String[] COLUMNS = {
            "service_running", "link_up", "hello", "peer_caps", "caps", "last_rx_age_ms", "version_code"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        CommsHub hub = CommsHub.peek();
        MatrixCursor c = new MatrixCursor(COLUMNS, 1);
        c.addRow(new Object[] {
                CommsService.running ? 1 : 0,
                hub != null && hub.isLinkUp() ? 1 : 0,
                hub != null && hub.hasPeerHello() ? 1 : 0,
                hub == null ? "" : hub.peerCapsCsv(),
                hub == null ? "" : hub.capsCsv(),
                hub == null ? -1L : hub.lastRxAgeMs(),
                versionCode()});
        return c;
    }

    private int versionCode() {
        try {
            PackageInfo pi = getContext().getPackageManager().getPackageInfo(
                    getContext().getPackageName(), 0);
            return (int) pi.getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    @Override public String getType(Uri uri) { return TYPE; }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
