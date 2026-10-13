package io.openshm.patcher.ui.screens.install

import io.openshm.patcher.R
import io.openshm.patcher.adb.BridgeStart
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.ui.components.CheckStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The phase of the Wireless debugging fallback the screen shows; null phase means it is not in use. */
internal fun SelfAdbState.toPhase(): SelfAdbPhase? = when (this) {
    SelfAdbState.Idle -> null
    SelfAdbState.WaitingForPairingService -> SelfAdbPhase.WaitingForService
    SelfAdbState.AmbiguousService -> SelfAdbPhase.Ambiguous
    is SelfAdbState.WaitingForCode -> if (lastFailure == null) SelfAdbPhase.WaitingForCode else SelfAdbPhase.WrongCode
    SelfAdbState.Reconnecting -> SelfAdbPhase.Reconnecting
    SelfAdbState.Pairing, SelfAdbState.WaitingForConnectService, SelfAdbState.Connecting -> SelfAdbPhase.Connecting
    is SelfAdbState.Connected -> SelfAdbPhase.Granting
    is SelfAdbState.Failed -> SelfAdbPhase.Failed
}

/**
 * Installs the phone outputs of the last build through the platform installer, one after the
 * other, and keeps the grant rows in step with what the system says. Plain code over
 * [PhoneInstaller] and [PhoneGrants] so it runs in JVM tests; [InstallPhoneViewModel] wraps it.
 *
 * Nothing is uninstalled unless the user confirms [reinstall] for a package whose copy another key
 * signed (never a system app, never a package outside the build). A run can be started again after a stop: installed items are not
 * sent again. Grants that only the user can change are not waited for: the run ends once every
 * package is installed, and [refresh] updates the rows when the screen is shown again.
 */
internal class PhoneInstallRunner(
    private val installer: PhoneInstaller,
    private val grants: PhoneGrants,
    private val build: () -> BuiltBuild?,
    private val prefix: () -> String,
    private val lockPrefix: () -> Unit,
    private val log: (String) -> Unit = {},
    /** The catalogue outputs, to recognise bridge copies of an earlier prefix; empty turns the cleanup off. */
    private val outputs: List<OutputApp> = emptyList(),
    private val ownPackage: String = "",
) {
    private var locked = false

    suspend fun run(state: MutableStateFlow<InstallUiState>) {
        locked = false
        state.update {
            it.copy(running = true, errorRes = null, errorDetail = null, needsUnknownSources = false, conflict = null, confirmingReinstall = false)
        }
        try {
            walk(state)
        } finally {
            state.update { it.copy(running = false) }
        }
        refresh(state)
    }

    private suspend fun walk(state: MutableStateFlow<InstallUiState>) {
        if (state.value.items.any { it.status != CheckStatus.Passed } && !installer.canInstallPackages()) {
            state.update { it.copy(needsUnknownSources = true) }
            fail(state, R.string.install_error_unknown_sources)
            return
        }
        val files = resolveBuildFiles(state, build(), prefix()) ?: return
        removeLeftovers()
        for (index in state.value.items.indices) {
            val item = state.value.items[index]
            if (item.status == CheckStatus.Passed) continue
            if (!install(state, index, files.getValue(item.outputId))) return
            startBridge(item)
        }
    }

    /**
     * Removes the bridge copies an earlier prefix left on the phone (found through the notification
     * listeners, which the phone bridge declares). The system may ask the user to confirm. System
     * packages are never touched. Failures are only logged, never fail the run.
     */
    private suspend fun removeLeftovers() {
        if (outputs.isEmpty()) return
        val leftovers = try {
            LeftoverBridges.find(outputs, Device.Phone, prefix(), installer.notificationListenerPackages(), setOf(ownPackage))
        } catch (e: RuntimeException) {
            log("leftover bridges not listed: ${e.javaClass.simpleName}")
            return
        }
        for (pkg in leftovers) {
            try {
                if (installer.packageKind(pkg) != PackageKind.User) continue
                when (val result = installer.uninstall(pkg)) {
                    UninstallResult.Success -> log("removed leftover bridge $pkg")
                    is UninstallResult.Failure -> log("leftover bridge $pkg not removed: ${result.message}")
                }
            } catch (e: IOException) {
                log("leftover bridge $pkg not removed: ${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                log("leftover bridge $pkg not removed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Starts a bridge once it is installed (clears the stopped state so boot delivery works from
     * then on). The grants the user still has to give do not matter for this. Never fails the run.
     */
    private fun startBridge(item: InstallItem) {
        if (item.outputId != BridgeStart.PHONE_OUTPUT) return
        try {
            installer.startBridge(item.packageName, BridgeStart.PHONE_RECEIVER, BridgeStart.action(prefix()))
        } catch (e: RuntimeException) {
            log("start request for ${item.outputId} failed: ${e.javaClass.simpleName}")
        }
    }

    /** True when the package is installed. */
    private suspend fun install(state: MutableStateFlow<InstallUiState>, index: Int, file: File): Boolean {
        val item = state.value.items[index]
        state.update { it.updateItem(index) { i -> i.copy(status = CheckStatus.Running, progress = 0f) } }
        // Hashes both files (tens of MB): off the main thread.
        if (withContext(Dispatchers.IO) { installer.isUpToDate(file, item.packageName) }) {
            // Left from an earlier run (the screen was recreated): same version and key, nothing to send.
            return markInstalled(state, index)
        }
        val result = try {
            installer.install(file, item.packageName) { fraction ->
                state.update { it.updateItem(index) { i -> i.copy(progress = fraction.coerceIn(0f, 1f)) } }
            }
        } catch (e: IOException) {
            state.update { it.updateItem(index) { i -> i.copy(status = CheckStatus.Failed, progress = null) } }
            fail(state, R.string.install_error_failed, e.message)
            return false
        }
        return when (result) {
            is InstallResult.Success -> markInstalled(state, index)
            is InstallResult.Failure -> {
                state.update { it.updateItem(index) { i -> i.copy(status = CheckStatus.Failed, progress = null) } }
                val detail = listOfNotNull(result.code, result.message.ifBlank { null }).distinct().joinToString(": ")
                val offered = isSignatureConflict(result.code, result.message) && offerReinstall(
                    state, index, Device.Phone,
                    owned = build().owns(item, prefix()),
                    kind = installer.packageKind(item.packageName),
                    detail = detail,
                )
                if (!offered) fail(state, installFailureRes(result.code), detail)
                false
            }
        }
    }

    /**
     * The user confirmed replacing the copy that another key signed: uninstalls it (the system asks
     * once more), then runs again so the install is retried. Does nothing without an offered conflict.
     */
    suspend fun reinstall(state: MutableStateFlow<InstallUiState>) {
        state.update { it.copy(running = true) }
        val removed = try {
            uninstallConflict(
                state,
                owns = { build().owns(it, prefix()) },
                kind = { installer.packageKind(it) },
                uninstall = installer::uninstall,
                ioError = { R.string.install_error_failed },
            )
        } finally {
            state.update { it.copy(running = false) }
        }
        if (removed) run(state)
    }

    private fun markInstalled(state: MutableStateFlow<InstallUiState>, index: Int): Boolean {
        state.update { it.updateItem(index) { i -> i.copy(status = CheckStatus.Passed, progress = null) } }
        if (!locked) {
            locked = true
            lockPrefix()
        }
        return true
    }

    /**
     * Re-reads the grant rows of the installed items from the system and clears the "allow
     * installing" stop once it was given. Does nothing while a run is in progress.
     */
    fun refresh(state: MutableStateFlow<InstallUiState>) {
        if (state.value.running) return
        state.update { current ->
            val items = current.items.map { item ->
                if (item.status == CheckStatus.Passed) item.copy(grants = grants.refresh(item.packageName, item.grants)) else item
            }
            val allowed = current.needsUnknownSources && installer.canInstallPackages()
            current.copy(
                items = items,
                needsUnknownSources = current.needsUnknownSources && !allowed,
                errorRes = if (allowed) null else current.errorRes,
                errorDetail = if (allowed) null else current.errorDetail,
            )
        }
    }

    /**
     * Applies the grants that did not pass to the installed items through [apply] (a shell on this
     * phone over Wireless debugging), then re-reads the rows. True when every required one passed.
     */
    suspend fun grantWithAdb(
        state: MutableStateFlow<InstallUiState>,
        apply: suspend (String, List<AdbGrant>) -> GrantReport,
    ): Boolean {
        state.update { it.copy(running = true, errorRes = null, errorDetail = null) }
        var ok = true
        try {
            for (index in state.value.items.indices) {
                if (state.value.items[index].status != CheckStatus.Passed) continue
                if (!applyItemGrants(state, index, apply) { R.string.install_error_grant }) {
                    ok = false
                    break
                }
            }
        } finally {
            state.update { it.copy(running = false) }
        }
        refresh(state)
        return ok
    }
}
