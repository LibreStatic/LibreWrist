package io.openshm.patcher.ui.screens.signingkey

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.MediaStoreBackupFiles
import io.openshm.patcher.data.PrefsExportedCertRecord
import io.openshm.patcher.data.privateBackups
import io.openshm.patcher.data.readBounded
import io.openshm.patcher.data.signingKeyStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wires [SigningKeyPresenter] to the system document pickers: the nav host collects [requests] into
 * the pickers and hands the chosen document back through [onBackupFileCreated] / [onKeyFilePicked].
 */
class SigningKeyViewModel(application: Application) : AndroidViewModel(application) {
    private val presenter = SigningKeyPresenter(signingKeyStore(application), PrefsExportedCertRecord(application), backupFiles = MediaStoreBackupFiles(application), privateBackups = privateBackups(application))

    val state: StateFlow<SigningKeyUiState> = presenter.state
    val requests: Flow<SigningKeyRequest> = presenter.requests

    init {
        viewModelScope.launch { presenter.refresh() }
    }

    fun onEvent(event: SigningKeyEvent) {
        if (event == SigningKeyEvent.ConfirmImport) {
            viewModelScope.launch { presenter.confirmImport() }
        } else {
            presenter.onEvent(event)
        }
    }

    /** The document the backup goes to; null when the user backed out. */
    fun onBackupFileCreated(uri: Uri?) {
        if (uri == null) {
            presenter.pickCancelled()
            return
        }
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            presenter.exportTo { bytes ->
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("the document cannot be opened")).use { it.write(bytes) }
            }
        }
    }

    /** Saves to Documents/OpenSHM with a typed password; the caller wipes both arrays. */
    suspend fun saveWithPassword(password: CharArray, confirm: CharArray): Boolean = presenter.saveWithPassword(password, confirm)

    /** The password of the private copy for the screen to reveal; the caller wipes it. */
    suspend fun localPassword(): CharArray? = presenter.localPassword()

    /** The document the private copy goes to; null when the user backed out. */
    fun onLocalBackupFileCreated(uri: Uri?) {
        if (uri == null) return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            presenter.exportLocalTo { bytes ->
                (resolver.openOutputStream(uri, "wt") ?: throw IOException("the document cannot be opened")).use { it.write(bytes) }
            }
        }
    }

    /** The chosen key file; null when the user backed out. */
    fun onKeyFilePicked(uri: Uri?) {
        if (uri == null) {
            presenter.pickCancelled()
            return
        }
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val fileName = withContext(Dispatchers.IO) { displayName(uri) }
            presenter.inspectFile(fileName) {
                (resolver.openInputStream(uri) ?: throw IOException("the document cannot be opened"))
                    .use { it.readBounded(MAX_KEY_FILE_BYTES) }
            }
        }
    }

    /** The file's display name (public, may carry a key digest), or null when it cannot be read. */
    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    override fun onCleared() = presenter.wipePending()

    private companion object {
        const val MAX_KEY_FILE_BYTES = 1 shl 20
    }
}
