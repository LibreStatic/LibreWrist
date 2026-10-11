package io.openshm.patcher.data

import android.content.Context

/** The certificate digest of the signing key the user last exported or imported, to know when a backup is due. */
interface ExportedCertRecord {
    var sha256: String?
}

/** What the automatic backup remembers on top of [ExportedCertRecord]. All values are public (digests and a file name). */
interface KeyBackupRecord : ExportedCertRecord {
    /** The file name of the last automatic backup. */
    var backupFile: String?

    /** The last backup is only the private copy in app storage (the password manager did not take the password). */
    var backupPrivate: Boolean

    /** The file name of the last private copy of the key; kept even after a later public backup. */
    var privateBackupFile: String?

    /** The certificate digest whose password manager prompt the user declined: no automatic prompt again. */
    var declinedSha256: String?
}

/** [ExportedCertRecord] in the "signing" SharedPreferences. Holds a public digest only. */
class PrefsExportedCertRecord(context: Context) : KeyBackupRecord {
    private val prefs = context.applicationContext.getSharedPreferences("signing", Context.MODE_PRIVATE)

    override var sha256: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().putString(KEY, value).apply()

    override var backupFile: String?
        get() = prefs.getString(BACKUP_FILE, null)
        set(value) = prefs.edit().putString(BACKUP_FILE, value).apply()

    override var backupPrivate: Boolean
        get() = prefs.getBoolean(BACKUP_PRIVATE, false)
        set(value) = prefs.edit().putBoolean(BACKUP_PRIVATE, value).apply()

    override var privateBackupFile: String?
        get() = prefs.getString(PRIVATE_FILE, null)
        set(value) = prefs.edit().putString(PRIVATE_FILE, value).apply()

    override var declinedSha256: String?
        get() = prefs.getString(DECLINED, null)
        set(value) = prefs.edit().putString(DECLINED, value).apply()

    private companion object {
        const val KEY = "exportedCertSha256"
        const val BACKUP_FILE = "keyBackupFile"
        const val BACKUP_PRIVATE = "keyBackupPrivate"
        const val PRIVATE_FILE = "keyBackupPrivateFile"
        const val DECLINED = "keyBackupDeclinedSha256"
    }
}

/** True when a key exists whose certificate was never exported (or was replaced since). Does disk and crypto work. */
fun signingKeyBackupDue(store: SigningKeyStore, record: ExportedCertRecord): Boolean =
    try {
        store.exists() && store.certificateSha256() != record.sha256
    } catch (e: java.io.IOException) {
        false
    }
