package io.openshm.patcher.ui.screens.signingkey

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.PrefsExportedCertRecord
import io.openshm.patcher.data.readBounded
import io.openshm.patcher.data.signingKeyStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Wires [SigningKeyPresenter] to the system document pickers: the nav host collects [requests] into
 * the pickers and hands the chosen document back through [onBackupFileCreated] / [onKeyFilePicked].
 */
class SigningKeyViewModel(application: Application) : AndroidViewModel(application) {
    private val presenter = SigningKeyPresenter(signingKeyStore(application), PrefsExportedCertRecord(application))

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

    /** The chosen key file; null when the user backed out. */
    fun onKeyFilePicked(uri: Uri?) {
        if (uri == null) {
            presenter.pickCancelled()
            return
        }
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            presenter.inspectFile {
                (resolver.openInputStream(uri) ?: throw IOException("the document cannot be opened"))
                    .use { it.readBounded(MAX_KEY_FILE_BYTES) }
            }
        }
    }

    override fun onCleared() = presenter.wipePending()

    private companion object {
        const val MAX_KEY_FILE_BYTES = 1 shl 20
    }
}
