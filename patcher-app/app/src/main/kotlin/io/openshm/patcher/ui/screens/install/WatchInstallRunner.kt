package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.BridgeStart
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.ui.components.CheckStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
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
 * are not sent again, nor are packages the watch already has from these exact bytes, and only grants that did not pass are applied again.
 */
internal class WatchInstallRunner(
    private val device: WatchDevice,
    private val build: () -> BuiltBuild?,
    private val prefix: () -> String,
    private val lockPrefix: () -> Unit,
    private val log: (String) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
    /** The catalogue outputs, to recognise bridge copies of an earlier prefix; empty turns the cleanup off. */
    private val outputs: List<OutputApp> = emptyList(),
    private val ownPackage: String = "",
    /** Called with the output id once that output is installed (Home's restored outputs forget it). */
    private val onInstalled: (String) -> Unit = {},
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
        // Reaching the watch (and the leftover cleanup) takes seconds: show the first pending item as
        // working right away instead of a screen that seems idle until its upload starts.
        val first = state.value.items.indexOfFirst { it.status != CheckStatus.Passed }
        if (first >= 0) state.update { it.updateItem(first) { item -> item.copy(status = CheckStatus.Running, progress = null) } }
        removeLeftovers()
        for (index in state.value.items.indices) {
            val item = state.value.items[index]
            if (item.status != CheckStatus.Passed) {
                if (!install(state, index, files.getValue(item.outputId))) return
            }
            if (!grant(state, index)) return
            startBridge(item)
        }
    }

    /**
     * Removes the bridge copies an earlier prefix left on the watch (they compete with the current
     * bridge for the phone link): companion associations first, then the package. System packages are
     * never touched. Failures are only logged, never fail the run.
     */
    private suspend fun removeLeftovers() {
        if (outputs.isEmpty()) return
        val leftovers = try {
            LeftoverBridges.find(outputs, Device.Watch, prefix(), device.listPackages(), setOf(ownPackage))
        } catch (e: IOException) {
            log("leftover bridges not listed: ${e.javaClass.simpleName}")
            return
        } catch (e: UnsupportedOperationException) {
            return
        }
        for (pkg in leftovers) {
            try {
                if (device.packageKind(pkg) != PackageKind.User) continue
                dropAssociations(pkg)
                when (val result = device.uninstall(pkg)) {
                    UninstallResult.Success -> log("removed leftover bridge $pkg")
                    is UninstallResult.Failure -> log("leftover bridge $pkg not removed: ${result.message}")
                }
            } catch (e: IOException) {
                log("leftover bridge $pkg not removed: ${e.javaClass.simpleName}")
            } catch (e: UnsupportedOperationException) {
                log("leftover bridge $pkg not removed: unsupported")
            }
        }
    }

    private suspend fun dropAssociations(pkg: String) {
        try {
            for (association in device.companionAssociations().filter { it.packageName == pkg }) {
                device.disassociateCompanion(pkg, association.mac)
            }
        } catch (e: IOException) {
            log("companion associations of $pkg not removed: ${e.javaClass.simpleName}")
        } catch (e: UnsupportedOperationException) {
            log("companion associations of $pkg not removed: unsupported")
        }
    }

    /** Starts a bridge once it is installed and granted (clears the stopped state). Never fails the run. */
    private suspend fun startBridge(item: InstallItem) {
        if (!BridgeStart.isBridge(item.outputId)) return
        try {
            device.startBridge(item.packageName, prefix())
        } catch (e: IOException) {
            log("start request for ${item.outputId} failed: ${e.javaClass.simpleName}")
            return
        } catch (e: IllegalArgumentException) {
            log("start request for ${item.outputId} rejected: ${e.javaClass.simpleName}")
            return
        }
        if (item.outputId == BridgeStart.WATCH_OUTPUT) CompanionLinker(device, log, pause).link(item.packageName, prefix())
    }

    /** True when the package is installed. */
    private suspend fun install(state: MutableStateFlow<InstallUiState>, index: Int, file: File): Boolean {
        val item = state.value.items[index]
        // No bar until bytes flow: the up-to-date check and install-create come first.
        state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Running, progress = null) } }
        val upToDate = try {
            device.isUpToDate(item.packageName, file)
        } catch (e: IOException) {
            false // the install below reports a broken link
        }
        if (upToDate) {
            log("${item.outputId} already installed from this build, not sent again")
            return markInstalled(state, index)
        }
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
            is InstallResult.Success -> markInstalled(state, index)
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

    private fun markInstalled(state: MutableStateFlow<InstallUiState>, index: Int): Boolean {
        state.update { it.updateItem(index) { item -> item.copy(status = CheckStatus.Passed, progress = null) } }
        if (!locked) {
            locked = true
            lockPrefix()
        }
        onInstalled(state.value.items[index].outputId)
        return true
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
