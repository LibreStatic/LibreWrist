package io.openshm.patcher.data

import android.content.Context

/** The certificate digest of the signing key the user last exported or imported, to know when a backup is due. */
interface ExportedCertRecord {
    var sha256: String?
}

/** [ExportedCertRecord] in the "signing" SharedPreferences. Holds a public digest only. */
class PrefsExportedCertRecord(context: Context) : ExportedCertRecord {
    private val prefs = context.applicationContext.getSharedPreferences("signing", Context.MODE_PRIVATE)

    override var sha256: String?
        get() = prefs.getString(KEY, null)
        set(value) = prefs.edit().putString(KEY, value).apply()

    private companion object {
        const val KEY = "exportedCertSha256"
    }
}

/** True when a key exists whose certificate was never exported (or was replaced since). Does disk and crypto work. */
fun signingKeyBackupDue(store: SigningKeyStore, record: ExportedCertRecord): Boolean =
    try {
        store.exists() && store.certificateSha256() != record.sha256
    } catch (e: java.io.IOException) {
        false
    }
