package shminterop.shmbridge.phone;

import android.content.ContentProvider;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Binder;
import android.os.Build;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only status of the bridge for the patcher app and other signature-level callers
 * (authority {@code shminterop.shmbridge.phone.status}, read permission
 * {@code shminterop.shmbridge.permission.STATUS}, declared in the manifest).
 *
 * <p>Callers are checked here rather than with {@code android:readPermission}: a caller is let in
 * when it holds the STATUS permission (same signer) or is signed with the certificate whose SHA-256
 * is in this provider's {@code PATCHER_CERT} meta-data, which the build sets to the patcher app's
 * certificate (the permission's {@code knownCerts} is a hidden API, hence the meta-data). The
 * patcher cannot request the permission itself, because its name carries the per-user prefix.</p>
 *
 * <p>A single row with the columns of {@link #COLUMNS}. It has no side effects: it never creates
 * the {@link CommsHub} nor starts the link or the service; without a hub it reports zeros and
 * empty strings. Nothing sensitive is exposed (state flags, capability names, an age, the
 * version).</p>
 */
public class StatusProvider extends ContentProvider {

    static final String AUTHORITY = "shminterop.shmbridge.phone.status";
    static final String TYPE = "vnd.android.cursor.item/vnd.shminterop.shmbridge.status";
    static final String STATUS_PERMISSION = "shminterop.shmbridge.permission.STATUS";
    /** Provider meta-data holding the patcher app's signing certificate digest (empty = none). */
    static final String PATCHER_CERT_KEY = "shminterop.shmbridge.PATCHER_CERT";
    static final String[] COLUMNS = {
            "service_running", "link_up", "hello", "peer_caps", "caps", "last_rx_age_ms", "version_code"};

    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        if (!callerAllowed()) throw new SecurityException("bridge status: caller not allowed");
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

    private boolean callerAllowed() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return true;
        if (getContext().checkCallingPermission(STATUS_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        // hasSigningCertificate(int, byte[], int) is API 28; the phone minSdk is 29 but keep the guard.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        byte[] digest = patcherDigest();
        return digest != null
                && getContext().getPackageManager().hasSigningCertificate(
                        uid, digest, PackageManager.CERT_INPUT_SHA256);
    }

    /** The patcher certificate digest from this provider's own meta-data; null when absent or malformed. */
    private byte[] patcherDigest() {
        try {
            ProviderInfo info = getContext().getPackageManager().getProviderInfo(
                    new ComponentName(getContext(), StatusProvider.class), PackageManager.GET_META_DATA);
            if (info.metaData == null) return null;
            String hex = info.metaData.getString(PATCHER_CERT_KEY);
            return hex == null ? null : digestBytes(hex);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** A SHA-256 digest written as 64 hex characters (colons allowed); null when it is not one. */
    static byte[] digestBytes(String hex) {
        String h = hex.replace(":", "");
        if (h.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(h.charAt(2 * i), 16);
            int lo = Character.digit(h.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
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
