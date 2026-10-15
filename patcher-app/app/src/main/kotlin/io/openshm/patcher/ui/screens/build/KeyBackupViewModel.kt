package io.openshm.patcher.ui.screens.build

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import io.openshm.patcher.data.CredentialManagerPasswords
import io.openshm.patcher.data.KeyBackup
import io.openshm.patcher.data.KeyBackupOutcome
import io.openshm.patcher.data.ManualKeyBackup
import io.openshm.patcher.data.cloudKeyBackup
import io.openshm.patcher.data.MediaStoreBackupFiles
import io.openshm.patcher.data.PrefsExportedCertRecord
import io.openshm.patcher.data.privateBackups
import io.openshm.patcher.data.signingKeyStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun KeyBackupOutcome.toStatus(): KeyBackupStatus = when (this) {
    KeyBackupOutcome.NoKey -> KeyBackupStatus.Hidden
    KeyBackupOutcome.AlreadyBackedUp, KeyBackupOutcome.Saved -> KeyBackupStatus.Saved
    KeyBackupOutcome.SavedPrivately -> KeyBackupStatus.PrivateOnly
    KeyBackupOutcome.SavedPrivatelyNoPasswordManager -> KeyBackupStatus.PrivateNoPasswordManager
    KeyBackupOutcome.PasswordDeclined -> KeyBackupStatus.Declined
    KeyBackupOutcome.PasswordNotSaved, KeyBackupOutcome.Failed -> KeyBackupStatus.Failed
}

/**
 * Runs the automatic key backup once per done screen: it keeps the outcome across a rotation so the
 * password manager is not asked again. The Activity is needed only for the system prompt and is not kept.
 */
class KeyBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableStateFlow(KeyBackupStatus.Hidden)
    val status: StateFlow<KeyBackupStatus> = _status.asStateFlow()
    private var started = false

    /** [force] is the user pressing "Save backup": it runs even after a decline and clears it. */
    suspend fun run(activity: Context, force: Boolean = false) {
        if (force) {
            if (_status.value == KeyBackupStatus.Saving) return
        } else {
            if (started) return
            started = true
        }
        val app = getApplication<Application>()
        val backup = KeyBackup(
            signingKeyStore(app), PrefsExportedCertRecord(app), CredentialManagerPasswords(activity), MediaStoreBackupFiles(app), privateBackups(app),
        )
        val before = _status.value
        _status.value = KeyBackupStatus.Saving
        try {
            _status.value = backup.run(force).toStatus()
        } catch (e: CancellationException) {
            // Left the screen mid-way: the next visit starts over.
            started = false
            _status.value = if (force) before else KeyBackupStatus.Hidden
            throw e
        }
        // The optional Google account copy, after the outcome above so it can never change it.
        try {
            cloudKeyBackup(app).save()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Optional layer.
        }
    }

    /** Saves with a typed password (Android 13 and below, where the password manager is not used); the caller wipes both arrays. */
    suspend fun saveWithPassword(password: CharArray, confirm: CharArray): Boolean {
        val app = getApplication<Application>()
        val result = ManualKeyBackup(signingKeyStore(app), PrefsExportedCertRecord(app), MediaStoreBackupFiles(app)).save(password, confirm)
        if (result == ManualKeyBackup.Result.Saved) _status.value = KeyBackupStatus.Saved
        return result == ManualKeyBackup.Result.Saved
    }
}
