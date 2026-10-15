package io.openshm.patcher.ui.screens.phoneadb

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.device.LaunchRequest
import io.openshm.patcher.device.PhoneSettings
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SelfAdbPurpose
import io.openshm.patcher.device.SelfAdbService
import io.openshm.patcher.device.SelfAdbSnapshot
import io.openshm.patcher.ui.screens.install.NEEDS_PAIRING_SCREEN
import io.openshm.patcher.ui.screens.install.SelfAdbPhase
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "PhoneAdb"

/** What the "Connect this phone" step shows. [phase] is null before the attempt starts and after a cancel. */
data class PhoneAdbUiState(
    val phase: SelfAdbPhase? = null,
    /** The platform's own text for a [SelfAdbPhase.Failed]; shown as detail. */
    val error: String? = null,
    /** The attempt failed because notifications are not allowed (the pairing code is typed into one). */
    val needsNotifications: Boolean = false,
    /** Connected and the app's own access is applied: the step moves on. */
    val done: Boolean = false,
)

/** Follows the controller's [snapshot] for an attempt of [purpose]; another purpose's progress is not ours to show. */
internal fun PhoneAdbUiState.reduce(snapshot: SelfAdbSnapshot, purpose: SelfAdbPurpose = SelfAdbPurpose.Setup): PhoneAdbUiState {
    if (snapshot.purpose != null && snapshot.purpose != purpose) return copy(phase = null, error = null, needsNotifications = false)
    if (snapshot.phase == SelfAdbPhase.Failed) {
        return copy(phase = SelfAdbPhase.Failed, error = snapshot.error, needsNotifications = snapshot.needsNotifications)
    }
    return copy(phase = snapshot.phase, error = null, needsNotifications = false)
}

/** Runtime permissions this app asks for, with the first API level that has them. */
private val OWN_RUNTIME_PERMISSIONS = listOf(
    Manifest.permission.BLUETOOTH_CONNECT to Build.VERSION_CODES.S,
    Manifest.permission.POST_NOTIFICATIONS to Build.VERSION_CODES.TIRAMISU,
)

/**
 * The own runtime permissions to grant over ADB: declared in the manifest ([declared]), existing on
 * this Android version ([sdk]) and not granted yet.
 */
internal fun ownPermissionsToGrant(declared: Set<String>, sdk: Int, isGranted: (String) -> Boolean): List<String> =
    OWN_RUNTIME_PERMISSIONS.filter { (permission, since) -> sdk >= since && permission in declared && !isGranted(permission) }
        .map { it.first }

/**
 * Runs `pm grant <pkg> <permission>` for each of [permissions] through [client]; a refusal is only
 * logged, since the step must not block on a permission the user can still give by hand. Returns the
 * permissions the shell accepted.
 */
internal suspend fun grantOwnPermissions(
    client: AdbClient,
    pkg: String,
    permissions: List<String>,
    log: (String) -> Unit = {},
): List<String> = buildList {
    for (permission in permissions) {
        try {
            val result = client.shell("pm grant $pkg $permission")
            if (result.isSuccess) add(permission) else log("pm grant $permission refused (exit ${result.exitCode})")
        } catch (e: IOException) {
            log("pm grant $permission failed: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * "Connect this phone": pairs this phone's own Wireless debugging (the same attempt the install
 * screen uses, as [SelfAdbPurpose.Setup]) and, once connected, grants this app its own runtime
 * permissions, so later steps do not ask for them. A paired phone reconnects with no code.
 */
class PhoneAdbViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(PhoneAdbUiState())
    val state: StateFlow<PhoneAdbUiState> = _state.asStateFlow()

    private val outgoing = Channel<LaunchRequest>(Channel.BUFFERED)

    /** Settings pages to start (Wireless debugging). */
    val launches: Flow<LaunchRequest> = outgoing.receiveAsFlow()

    /** Wireless debugging opens only once the stored pairing could not reconnect. */
    private var settingsOpened = false

    init {
        viewModelScope.launch { SelfAdbController.state.collect(::onAdb) }
    }

    fun begin() {
        if (_state.value.done || !SelfAdbController.begin(SelfAdbPurpose.Setup)) return
        settingsOpened = false
        SelfAdbService.start(getApplication())
    }

    fun submitCode(code: String) = SelfAdbController.submitCode(code)

    /** Also clears a failed attempt (which is no longer active), back to the start of the step. */
    fun cancel() {
        if (SelfAdbController.state.value.purpose == SelfAdbPurpose.Setup) SelfAdbController.finish()
    }

    fun openWirelessDebugging() {
        outgoing.trySend(PhoneSettings.wirelessDebugging())
    }

    private fun onAdb(snapshot: SelfAdbSnapshot) {
        if (_state.value.done) return
        _state.update { it.reduce(snapshot) }
        if (snapshot.purpose != SelfAdbPurpose.Setup) return
        if (snapshot.phase in NEEDS_PAIRING_SCREEN && !settingsOpened) {
            settingsOpened = true
            openWirelessDebugging()
        }
        if (snapshot.phase == SelfAdbPhase.Granting) {
            val client = SelfAdbController.takeClient(SelfAdbPurpose.Setup) ?: return
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val declared = declaredPermissions(app)
                    val todo = ownPermissionsToGrant(declared, Build.VERSION.SDK_INT) {
                        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
                    }
                    val granted = grantOwnPermissions(client, app.packageName, todo) { Log.w(TAG, it) }
                    Log.i(TAG, "own permissions granted: $granted")
                } catch (e: RuntimeException) {
                    Log.w(TAG, "own permissions not granted", e)
                } finally {
                    SelfAdbController.finish()
                    _state.update { it.copy(phase = null, done = true) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun declaredPermissions(app: Application): Set<String> = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions.orEmpty().toSet()
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }

    override fun onCleared() {
        SelfAdbController.finishIfOwned(SelfAdbPurpose.Setup)
    }
}
