package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.BridgeStart
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.ui.components.CheckStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Maps a failed ADB call to the message the install and disable screens show. */
@StringRes
internal fun watchErrorRes(e: IOException): Int = when (e) {
    is AdbException.PairingRequired, is AdbException.AuthenticationRejected, is AdbException.RestartRequired ->
        R.string.watch_error_pairing
    else -> R.string.watch_error_unreachable
}

/**
 * Installs the watch outputs of the last build, one after the other, and applies each one's grants.
 * Plain code over [WatchDevice] so it runs in JVM tests; [InstallWatchViewModel] only wraps it.
 *
 * Nothing is uninstalled unless the user confirms [reinstall] for a package whose copy another key
 * signed (never a system package, never a package outside the build). A run can be started again after a stop: items that are installed
 * are not sent again, and only grants that did not pass are applied again.
 */
internal class WatchInstallRunner(
    private val device: WatchDevice,
    private val build: () -> BuiltBuild?,
    private val prefix: () -> String,
    private val lockPrefix: () -> Unit,
    private val log: (String) -> Unit = {},
) {
    private var locked = false

    suspend fun run(state: MutableStateFlow<InstallUiState>) {
        locked = false
        state.update { it.copy(running = true, errorRes = null, errorDetail = null, conflict = null, confirmingReinstall = false) }
        try {
            walk(state)
        } finally {
            state.update { it.copy(running = false) }
        }
    }

    private suspend fun walk(state: MutableStateFlow<InstallUiState>) {
        val files = resolveBuildFiles(state, build(), prefix()) ?: return
        for (index in state.value.items.indices) {
            val item = state.value.items[index]
            if (item.status != CheckStatus.Passed) {
                if (!install(state, index, files.getValue(item.outputId))) return
            }
            if (!grant(state, index)) return
            startBridge(item)
        }
    }

    /** Starts a bridge once it is installed and granted (clears the stopped state). Never fails the run. */
    private suspend fun startBridge(item: InstallItem) {
        if (!BridgeStart.isBridge(item.outputId)) return
        try {
            device.startBridge(item.packageName, prefix())
        } catch (e: IOException) {
            log("start request for ${item.outputId} failed: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            log("start request for ${item.outputId} rejected: ${e.javaClass.simpleName}")
        }
    }

    /** True when the package is installed. */
    private suspend fun install(state: MutableStateFlow<InstallUiState>, index: Int, file: File): Boolean {
        val item = state.value.items[index]
        state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Running, progress = 0f) } }
        val result = try {
            device.install(file) { fraction ->
                state.update { it.updateItem(index) { item -> item.copy(progress = fraction.coerceIn(0f, 1f)) } }
            }
        } catch (e: IOException) {
            state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Failed, progress = null) } }
            fail(state, if (e is AdbException) watchErrorRes(e) else R.string.install_error_failed, e.message)
            return false
        }
        return when (result) {
            is InstallResult.Success -> {
                state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Passed, progress = null) } }
                if (!locked) {
                    locked = true
                    lockPrefix()
                }
                true
            }
            is InstallResult.Failure -> {
                state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Failed, progress = null) } }
                val detail = listOfNotNull(result.code, result.message).distinct().joinToString(": ")
                val offered = isSignatureConflict(result.code, result.message) && offerReinstall(
                    state, index, Device.Watch,
                    owned = build().owns(item, prefix()),
                    kind = try {
                        device.packageKind(item.packageName)
                    } catch (e: IOException) {
                        PackageKind.System // cannot tell: do not offer a removal
                    },
                    detail = detail,
                )
                if (!offered) fail(state, installFailureRes(result.code), detail)
                false
            }
        }
    }

    /**
     * The user confirmed replacing the copy that another key signed: `pm uninstall` it, then run
     * again so the install is retried. Does nothing without an offered conflict.
     */
    suspend fun reinstall(state: MutableStateFlow<InstallUiState>) {
        state.update { it.copy(running = true) }
        val removed = try {
            uninstallConflict(
                state,
                owns = { build().owns(it, prefix()) },
                kind = device::packageKind,
                uninstall = device::uninstall,
                ioError = ::watchErrorRes,
            )
        } finally {
            state.update { it.copy(running = false) }
        }
        if (removed) run(state)
    }

    private suspend fun grant(state: MutableStateFlow<InstallUiState>, index: Int): Boolean =
        applyItemGrants(state, index, device::applyGrants) { e ->
            if (e is AdbException) watchErrorRes(e) else R.string.install_error_grant
        }
}
