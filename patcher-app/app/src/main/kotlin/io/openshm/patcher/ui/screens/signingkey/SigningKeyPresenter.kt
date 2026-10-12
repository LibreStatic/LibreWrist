package io.openshm.patcher.ui.screens.signingkey

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.data.BackupFiles
import io.openshm.patcher.data.KeyBackupRecord
import io.openshm.patcher.data.ManualKeyBackup
import io.openshm.patcher.data.PrivateBackups
import io.openshm.patcher.data.KeyImportException
import io.openshm.patcher.data.SigningKeyStore
import io.openshm.patcher.data.keyBackupDigestOf
import io.openshm.patcher.data.keyBackupShortDigest
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class SigningKeyForm { None, Export, Import }

/** A result line shown on the screen; only ever a string resource, never text from an exception. */
data class SigningKeyMessage(@StringRes val text: Int, val isError: Boolean)

/** What replacing the key would do; both are lowercase hex SHA-256 digests of the certificates. */
data class ImportConfirmation(val currentSha256: String?, val importedSha256: String)

/**
 * Everything the screen shows. It holds public digests and resource ids only: passwords and key bytes
 * live in [SigningKeyPresenter] for the time a pending action needs them and are wiped afterwards.
 */
data class SigningKeyUiState(
    val loading: Boolean = true,
    val keyExists: Boolean = false,
    /** The key exists but cannot be unlocked on this device. */
    val unreadable: Boolean = false,
    val certificateSha256: String? = null,
    /** The current certificate is the one last exported or imported. */
    val backedUp: Boolean = false,
    /** The file name of the automatic backup of the current key, when it is in the public folder. */
    val autoBackupFile: String? = null,
    /** The file name of the private copy of the current key in app storage, when there is one. */
    val localBackup: String? = null,
    val form: SigningKeyForm = SigningKeyForm.None,
    @StringRes val formError: Int? = null,
    val busy: Boolean = false,
    val message: SigningKeyMessage? = null,
    val confirmation: ImportConfirmation? = null,
    /** The saved password picked for an import belongs to a different key than the chosen file. */
    val savedPasswordMismatch: Boolean = false,
)

/** What the nav host asks the system document picker for. */
enum class SigningKeyRequest { CreateBackupFile, PickKeyFile, CreateLocalBackupFile }

/** Raw passwords come in as char arrays that the receiver wipes. */
sealed interface SigningKeyEvent {
    data object OpenExport : SigningKeyEvent
    data object ExportLocalBackup : SigningKeyEvent
    data object OpenImport : SigningKeyEvent
    data object CloseForm : SigningKeyEvent
    class SubmitExport(val password: CharArray, val confirm: CharArray) : SigningKeyEvent
    /** [savedCredentialId] is the id of the saved password the user picked, if any (public text). */
    class SubmitImport(val password: CharArray, val savedCredentialId: String? = null) : SigningKeyEvent
    data object ConfirmImport : SigningKeyEvent
    data object DismissConfirmation : SigningKeyEvent
    data object DismissMessage : SigningKeyEvent
}

/** The rule for the password of an exported backup. */
object ExportPasswordPolicy {
    const val MIN_LENGTH = io.openshm.patcher.data.ManualKeyBackup.MIN_PASSWORD_LENGTH

    /** The user-facing problem with the pair, or null when it is acceptable. */
    @StringRes
    fun problem(password: CharArray, confirm: CharArray): Int? = when {
        password.size < MIN_LENGTH -> R.string.signing_key_error_short
        !password.contentEquals(confirm) -> R.string.signing_key_error_mismatch
        else -> null
    }

    /**
     * The problem to show while the user types: a field is judged once the user has left it ([passwordLeft],
     * [confirmLeft]), and an error from an earlier submit ([submitted]) stays only while it still applies.
     */
    @StringRes
    fun shownProblem(password: String, confirm: String, passwordLeft: Boolean, confirmLeft: Boolean, @StringRes submitted: Int?): Int? {
        val short = password.length < MIN_LENGTH
        val mismatch = password != confirm
        return when {
            passwordLeft && password.isNotEmpty() && short -> R.string.signing_key_error_short
            confirmLeft && confirm.isNotEmpty() && mismatch -> R.string.signing_key_error_mismatch
            submitted == R.string.signing_key_error_short -> submitted.takeIf { short }
            submitted == R.string.signing_key_error_mismatch -> submitted.takeIf { mismatch }
            else -> submitted
        }
    }
}

/** A digest in groups of four, upper case, for reading and comparing by eye. */
fun formatDigest(hex: String): String = hex.uppercase().chunked(4).joinToString(" ")

/**
 * The logic behind the signing key screen, free of Android so it runs in JVM tests. The platform side
 * (document pickers, content resolver) is injected per call: the view model passes lambdas that read
 * or write the chosen document. Blocking work (PKCS#12, key derivation, file IO) runs on [io].
 *
 * Security notes: passwords are copied into [pendingExportPassword] / [pendingImportPassword] only
 * between the form submit and the end of the picker round trip, and arrays handed in or held are
 * overwritten once used. Nothing here logs, and no message is built from exception text.
 */
class SigningKeyPresenter(
    private val store: SigningKeyStore,
    private val record: KeyBackupRecord,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where the automatic backup lives; null skips looking for it. */
    private val backupFiles: BackupFiles? = null,
    /** The private copies of automatic backups; null skips looking for them. */
    private val privateBackups: PrivateBackups? = null,
) {
    private val _state = MutableStateFlow(SigningKeyUiState())
    val state: StateFlow<SigningKeyUiState> = _state.asStateFlow()

    private val requestChannel = Channel<SigningKeyRequest>(Channel.BUFFERED)
    val requests: Flow<SigningKeyRequest> = requestChannel.receiveAsFlow()

    private var pendingExportPassword: CharArray? = null
    private var pendingImportPassword: CharArray? = null
    private var pendingImportBytes: ByteArray? = null
    private var pendingSavedDigest: String? = null

    suspend fun refresh() {
        val snapshot = withContext(io) {
            val exists = store.exists()
            if (!exists) return@withContext Snapshot(false, false, null, null, null)
            try {
                val sha = store.certificateSha256()
                val local = record.privateBackupFile
                    ?.takeIf { keyBackupDigestOf(it) == keyBackupShortDigest(sha) && privateBackups?.let { p -> runCatching { p.exists(it) }.getOrDefault(false) } == true }
                val file = record.backupFile?.takeIf { !record.backupPrivate && keyBackupDigestOf(it) == keyBackupShortDigest(sha) }
                val present = file != null && backupFiles?.let { runCatching { it.exists(file) }.getOrDefault(false) } == true
                Snapshot(true, false, sha, file.takeIf { present }, local)
            } catch (e: IOException) {
                Snapshot(true, true, null, null, null)
            } catch (e: java.security.GeneralSecurityException) {
                Snapshot(true, true, null, null, null)
            }
        }
        val exported = record.sha256
        _state.update {
            it.copy(
                loading = false,
                keyExists = snapshot.exists,
                unreadable = snapshot.unreadable,
                certificateSha256 = snapshot.sha256,
                backedUp = snapshot.sha256 != null && snapshot.sha256 == exported,
                autoBackupFile = snapshot.autoBackupFile,
                localBackup = snapshot.localBackup,
            )
        }
    }

    fun onEvent(event: SigningKeyEvent) {
        when (event) {
            SigningKeyEvent.OpenExport -> openForm(SigningKeyForm.Export)
            SigningKeyEvent.OpenImport -> openForm(SigningKeyForm.Import)
            SigningKeyEvent.ExportLocalBackup -> if (_state.value.localBackup != null) requestChannel.trySend(SigningKeyRequest.CreateLocalBackupFile)
            SigningKeyEvent.CloseForm -> closeForm()
            is SigningKeyEvent.SubmitExport -> submitExport(event.password, event.confirm)
            is SigningKeyEvent.SubmitImport -> submitImport(event.password, event.savedCredentialId)
            SigningKeyEvent.DismissConfirmation -> dismissConfirmation()
            SigningKeyEvent.DismissMessage -> _state.update { it.copy(message = null) }
            // Confirmation does work and is a suspend call, see confirmImport.
            SigningKeyEvent.ConfirmImport -> Unit
        }
    }

    private fun openForm(form: SigningKeyForm) {
        wipePending()
        _state.update { if (it.busy || (form == SigningKeyForm.Export && !it.keyExists)) it else it.copy(form = form, formError = null, message = null, savedPasswordMismatch = false) }
    }

    private fun closeForm() {
        wipePending()
        _state.update { it.copy(form = SigningKeyForm.None, formError = null, savedPasswordMismatch = false) }
    }

    private fun submitExport(password: CharArray, confirm: CharArray) {
        try {
            val problem = ExportPasswordPolicy.problem(password, confirm)
            if (problem != null) {
                _state.update { it.copy(formError = problem) }
                return
            }
            wipePending()
            pendingExportPassword = password.copyOf()
            _state.update { it.copy(formError = null) }
            requestChannel.trySend(SigningKeyRequest.CreateBackupFile)
        } finally {
            password.fill('\u0000')
            confirm.fill('\u0000')
        }
    }

    private fun submitImport(password: CharArray, savedCredentialId: String?) {
        try {
            if (password.isEmpty()) {
                _state.update { it.copy(formError = R.string.signing_key_error_empty) }
                return
            }
            wipePending()
            pendingImportPassword = password.copyOf()
            pendingSavedDigest = savedCredentialId?.let(::keyBackupDigestOf)
            _state.update { it.copy(formError = null, savedPasswordMismatch = false) }
            requestChannel.trySend(SigningKeyRequest.PickKeyFile)
        } finally {
            password.fill('\u0000')
        }
    }

    /** Saves the backup to the public folder with the typed password; the caller wipes both arrays. Returns true when saved. */
    suspend fun saveWithPassword(password: CharArray, confirm: CharArray): Boolean {
        val files = backupFiles ?: return false
        _state.update { it.copy(busy = true, message = null) }
        val result = ManualKeyBackup(store, record, files, io).save(password, confirm)
        val ok = result == ManualKeyBackup.Result.Saved
        _state.update { it.copy(busy = false, message = SigningKeyMessage(if (ok) R.string.signing_key_export_done else R.string.signing_key_export_failed, !ok)) }
        if (ok) refresh()
        return ok
    }

    /** The generated password of the private copy, as a char array the caller wipes; null when there is none. */
    suspend fun localPassword(): CharArray? {
        val name = _state.value.localBackup ?: return null
        return try {
            withContext(io) { privateBackups?.readPassword(name) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Hands the private copy's PKCS#12 to [write], which saves it to the chosen document. */
    suspend fun exportLocalTo(write: (ByteArray) -> Unit) {
        val name = _state.value.localBackup ?: return
        _state.update { it.copy(busy = true, message = null) }
        var bytes: ByteArray? = null
        val ok = try {
            withContext(io) {
                bytes = privateBackups?.readFile(name) ?: throw IOException("the private backup is missing")
                write(bytes!!)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            bytes?.fill(0)
        }
        _state.update { it.copy(busy = false, message = SigningKeyMessage(if (ok) R.string.signing_key_export_done else R.string.signing_key_export_failed, !ok)) }
    }

    /** The user closed a picker without choosing a file. */
    fun pickCancelled() = wipePending()

    /** Builds the backup with the password from the form and hands it to [write], which saves it to the chosen document. */
    suspend fun exportTo(write: (ByteArray) -> Unit) {
        val password = pendingExportPassword ?: return
        pendingExportPassword = null
        _state.update { it.copy(busy = true, message = null) }
        var bytes: ByteArray? = null
        val ok = try {
            withContext(io) {
                bytes = store.exportPkcs12(password)
                write(bytes!!)
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            password.fill('\u0000')
            bytes?.fill(0)
        }
        if (ok) {
            val sha = runCatching { withContext(io) { store.certificateSha256() } }.getOrNull()
            if (sha != null) record.sha256 = sha
        }
        _state.update {
            it.copy(
                busy = false,
                form = if (ok) SigningKeyForm.None else it.form,
                message = SigningKeyMessage(if (ok) R.string.signing_key_export_done else R.string.signing_key_export_failed, !ok),
            )
        }
        if (ok) refresh()
    }

    /** Reads the chosen file with [read], checks it with the password from the form and asks for confirmation. */
    suspend fun inspectFile(fileName: String? = null, read: () -> ByteArray) {
        val password = pendingImportPassword ?: return
        // Both digests must be known to call a saved password foreign; they are public names, not secrets.
        val fileDigest = fileName?.let(::keyBackupDigestOf)
        val mismatch = pendingSavedDigest != null && fileDigest != null && pendingSavedDigest != fileDigest
        _state.update { it.copy(busy = true, message = null, savedPasswordMismatch = mismatch) }
        var failure: Int?
        var imported: String? = null
        var bytes: ByteArray? = null
        try {
            withContext(io) {
                bytes = read()
                imported = store.inspectPkcs12(bytes!!, password)
            }
            failure = null
        } catch (e: CancellationException) {
            password.fill('\u0000')
            bytes?.fill(0)
            pendingImportPassword = null
            throw e
        } catch (e: KeyImportException) {
            failure = e.reason.messageRes()
        } catch (e: Exception) {
            failure = R.string.signing_key_import_unreadable
        }
        if (failure != null || imported == null) {
            password.fill('\u0000')
            pendingImportPassword = null
            bytes?.fill(0)
            _state.update { it.copy(busy = false, formError = failure ?: R.string.signing_key_import_unreadable) }
            return
        }
        pendingImportBytes = bytes
        _state.update { it.copy(busy = false, confirmation = ImportConfirmation(it.certificateSha256, imported!!)) }
    }

    /** Replaces the key after the user accepted the confirmation. */
    suspend fun confirmImport() {
        val password = pendingImportPassword
        val bytes = pendingImportBytes
        if (password == null || bytes == null) {
            dismissConfirmation()
            return
        }
        val confirmation = _state.value.confirmation
        pendingImportPassword = null
        pendingImportBytes = null
        _state.update { it.copy(busy = true, confirmation = null) }
        val ok = try {
            withContext(io) { store.importPkcs12(ByteArrayInputStream(bytes), password) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        } finally {
            password.fill('\u0000')
            bytes.fill(0)
        }
        // The user holds the file the key came from, so it counts as backed up.
        if (ok && confirmation != null) record.sha256 = confirmation.importedSha256
        _state.update {
            it.copy(
                busy = false,
                form = if (ok) SigningKeyForm.None else it.form,
                message = SigningKeyMessage(if (ok) R.string.signing_key_import_done else R.string.signing_key_import_failed, !ok),
            )
        }
        refresh()
    }

    fun dismissConfirmation() {
        wipePending()
        _state.update { it.copy(confirmation = null) }
    }

    /** Wipes everything secret held for a pending action. */
    fun wipePending() {
        pendingExportPassword?.fill('\u0000')
        pendingImportPassword?.fill('\u0000')
        pendingImportBytes?.fill(0)
        pendingExportPassword = null
        pendingImportPassword = null
        pendingImportBytes = null
        pendingSavedDigest = null
    }

    private class Snapshot(val exists: Boolean, val unreadable: Boolean, val sha256: String?, val autoBackupFile: String?, val localBackup: String?)
}

@StringRes
internal fun KeyImportException.Reason.messageRes(): Int = when (this) {
    KeyImportException.Reason.WrongPasswordOrNotPkcs12 -> R.string.signing_key_import_wrong_password
    KeyImportException.Reason.NotPkcs12 -> R.string.signing_key_import_unreadable
    KeyImportException.Reason.NotOneKey -> R.string.signing_key_import_not_one_key
    KeyImportException.Reason.NoUsableKey, KeyImportException.Reason.NoCertificate -> R.string.signing_key_import_unusable
    KeyImportException.Reason.WeakKey -> R.string.signing_key_import_weak
    KeyImportException.Reason.UnsupportedKeyType -> R.string.signing_key_import_type
    KeyImportException.Reason.KeyCertificateMismatch -> R.string.signing_key_import_mismatch
    KeyImportException.Reason.TooManyIterations -> R.string.signing_key_import_costly
}
