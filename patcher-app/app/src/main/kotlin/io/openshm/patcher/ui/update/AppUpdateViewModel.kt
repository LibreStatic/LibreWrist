package io.openshm.patcher.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.data.AppUpdate
import io.openshm.patcher.data.AppUpdateDownload
import io.openshm.patcher.data.AppUpdateDownloader
import io.openshm.patcher.data.AppUpdateError
import io.openshm.patcher.data.AppUpdatePrefs
import io.openshm.patcher.data.AppUpdateResult
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SessionPhoneInstaller
import io.openshm.patcher.update.AppUpdateNotifier
import io.openshm.patcher.update.AppUpdateRequests
import io.openshm.patcher.update.AppUpdateScheduler
import io.openshm.patcher.update.newAppUpdateChecker
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the update dialog shows. The process ends when an install commits, so there is no "done". */
sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data class Available(val update: AppUpdate) : AppUpdateUiState
    /** Installing from this app is not allowed yet; the system page for it is open. */
    data class NeedsPermission(val update: AppUpdate) : AppUpdateUiState
    data class Downloading(val update: AppUpdate, val progress: Float) : AppUpdateUiState
    data class Verifying(val update: AppUpdate) : AppUpdateUiState
    data class Installing(val update: AppUpdate, val progress: Float) : AppUpdateUiState
    data class Error(val update: AppUpdate, val error: AppUpdateError, val detail: String? = null) : AppUpdateUiState
}

/** The answer of a manual check that produced no dialog. */
enum class UpdateNotice { UpToDate, CheckFailed }

/**
 * Activity-scoped: checks the release channel at startup, runs a manual check for Settings and drives
 * the download, verification and install of a newer build. The confirmation of the system installer
 * goes out through [launches], which [io.openshm.patcher.ui.PatcherApp] starts from the activity.
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppUpdatePrefs(application)
    private val checker = newAppUpdateChecker(application, settings)
    private val downloader = AppUpdateDownloader(application)
    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)
    private val installer = SessionPhoneInstaller(application) { outgoing.trySend(LaunchRequest(it)) }

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Idle)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val _notice = MutableStateFlow<UpdateNotice?>(null)
    val notice: StateFlow<UpdateNotice?> = _notice.asStateFlow()

    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    /** False when this build has no update repository: nothing is ever requested. */
    val available: Boolean get() = checker.enabled

    private var startupChecked = false
    private var dismissedBuild: Long? = null
    private var lastAvailable: AppUpdate? = null
    private var job: Job? = null
    private val closer = AtomicReference<(() -> Unit)?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            downloader.cleanup()
            AppUpdateScheduler.sync(application)
        }
        // A tap on the "new version" notification: show the dialog even for a build dismissed with "Later".
        viewModelScope.launch {
            AppUpdateRequests.openUpdater.collect { requested ->
                if (requested) {
                    AppUpdateRequests.consume()
                    openFromNotification()
                }
            }
        }
    }

    private fun openFromNotification() {
        val cached = lastAvailable
        if (cached != null) {
            dismissedBuild = null
            offer(cached)
        } else {
            checkNow()
        }
    }

    /** Once per process: the check the user allowed, at most once an hour. */
    fun checkAtStartup() {
        if (startupChecked) return
        startupChecked = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { checker.checkAtStartup() }
            if (result is AppUpdateResult.Available) {
                lastAvailable = result.update
                if (result.update.build != dismissedBuild) offer(result.update)
            }
        }
    }

    /** Settings' "Check now": ignores the switch and the hourly limit. */
    fun checkNow() {
        if (_checking.value || !checker.enabled || _state.value != AppUpdateUiState.Idle) return
        _checking.value = true
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { checker.checkNow() }
            } finally {
                _checking.value = false
            }
            when (result) {
                is AppUpdateResult.Available -> {
                    lastAvailable = result.update
                    offer(result.update)
                }
                AppUpdateResult.UpToDate -> _notice.value = UpdateNotice.UpToDate
                is AppUpdateResult.Failed -> _notice.value = UpdateNotice.CheckFailed
                AppUpdateResult.Disabled -> Unit
            }
        }
    }

    fun noticeShown() {
        _notice.value = null
    }

    private fun offer(update: AppUpdate) {
        if (_state.value == AppUpdateUiState.Idle) {
            _state.value = AppUpdateUiState.Available(update)
            AppUpdateNotifier.cancel(getApplication())
        }
    }

    /** "Later": hides the offer until the next start. */
    fun dismiss() {
        (_state.value as? AppUpdateUiState.Available)?.let { dismissedBuild = it.update.build }
        _state.value = AppUpdateUiState.Idle
    }

    /** Closes the dialog from an error or the permission step; the same build is offered again next start. */
    fun close() {
        job?.cancel()
        _state.value = AppUpdateUiState.Idle
    }

    /** "Update" / "Continue" / "Try again". */
    fun start() {
        val update = when (val s = _state.value) {
            is AppUpdateUiState.Available -> s.update
            is AppUpdateUiState.NeedsPermission -> s.update
            is AppUpdateUiState.Error -> s.update
            else -> return
        }
        if (!installer.canInstallPackages()) {
            _state.value = AppUpdateUiState.NeedsPermission(update)
            outgoing.trySend(PhoneSettings.unknownSources(getApplication()))
            return
        }
        _state.value = AppUpdateUiState.Downloading(update, 0f)
        job = viewModelScope.launch { run(update) }
    }

    /** Stops a running download and goes back to the offer. */
    fun cancel() {
        val update = when (val s = _state.value) {
            is AppUpdateUiState.Downloading -> s.update
            is AppUpdateUiState.Verifying -> s.update
            else -> return
        }
        closer.get()?.invoke()
        job?.cancel()
        viewModelScope.launch(Dispatchers.IO) { downloader.cleanup() }
        _state.value = AppUpdateUiState.Available(update)
    }

    private suspend fun run(update: AppUpdate) {
        try {
            val download = withContext(Dispatchers.IO) {
                downloader.download(
                    update,
                    onProgress = { p -> _state.value = AppUpdateUiState.Downloading(update, p) },
                    onOpen = { closer.set(it) },
                    onVerifying = { _state.value = AppUpdateUiState.Verifying(update) },
                )
            }
            val file = when (download) {
                is AppUpdateDownload.Failed -> {
                    _state.value = AppUpdateUiState.Error(update, download.error)
                    return
                }
                is AppUpdateDownload.Ready -> download.file
            }
            _state.value = AppUpdateUiState.Installing(update, 0f)
            val result = installer.install(file, getApplication<Application>().packageName) { p ->
                _state.value = AppUpdateUiState.Installing(update, p)
            }
            // Success replaces this process, so this line is reached only when the install did not commit.
            _state.value = when (result) {
                is InstallResult.Success -> AppUpdateUiState.Idle
                is InstallResult.Failure -> AppUpdateUiState.Error(update, AppUpdateError.InstallFailed, result.code ?: result.message.ifBlank { null })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = AppUpdateUiState.Error(update, AppUpdateError.InstallFailed, e.message)
        } finally {
            closer.set(null)
        }
    }
}
