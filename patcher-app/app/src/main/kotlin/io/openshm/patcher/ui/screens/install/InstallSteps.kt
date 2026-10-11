package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.data.Device
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.toAdbGrant
import io.openshm.patcher.ui.components.CheckStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/* Steps the watch and phone install runners share. */

internal fun InstallUiState.updateItem(index: Int, change: (InstallItem) -> InstallItem) =
    copy(items = items.mapIndexed { i, item -> if (i == index) change(item) else item })

internal fun fail(state: MutableStateFlow<InstallUiState>, @StringRes res: Int, detail: String? = null) {
    state.update { it.copy(errorRes = res, errorDetail = detail) }
}

/** The message for a refused install; a copy with another signature is called out. */
@StringRes
internal fun installFailureRes(code: String?): Int =
    if (code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE") R.string.install_error_signature else R.string.install_error_failed

/**
 * True when a refused install means another key signed the installed copy: the package manager's
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (also found in free text, as `pm install` prints it) or the
 * installer session's `STATUS_FAILURE_CONFLICT`.
 */
internal fun isSignatureConflict(code: String?, message: String? = null): Boolean =
    code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE" || code == "STATUS_FAILURE_CONFLICT" ||
        message?.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") == true ||
        message?.contains("signatures do not match", ignoreCase = true) == true

/** True when [item] is one of the outputs of the last build for the current prefix (so it is ours). */
internal fun BuiltBuild?.owns(item: InstallItem, prefix: String): Boolean =
    this != null && this.prefix == prefix && outputs.any { it.outputId == item.outputId }

/**
 * Shows the signature error of item [index] with the "uninstall and install" fallback, and returns
 * true, when the installed copy is a user app ([kind] is [PackageKind.User]) and the item is ours.
 * Otherwise returns false and shows nothing: the caller reports the plain error.
 */
internal fun offerReinstall(
    state: MutableStateFlow<InstallUiState>,
    index: Int,
    device: Device,
    owned: Boolean,
    kind: PackageKind,
    detail: String?,
): Boolean {
    if (!owned || kind != PackageKind.User) return false
    val item = state.value.items[index]
    state.update {
        it.copy(
            conflict = SignatureConflict(item.outputId, item.packageName, device),
            confirmingReinstall = false,
            errorRes = if (device == Device.Phone) R.string.reinstall_error_phone else R.string.reinstall_error_watch,
            errorDetail = detail,
        )
    }
    return true
}

/** Opens the confirmation. Does nothing unless a conflict is offered and no run is in progress. */
internal fun requestReinstall(state: MutableStateFlow<InstallUiState>) {
    state.update { if (it.conflict != null && !it.running) it.copy(confirmingReinstall = true) else it }
}

/** Closes the confirmation; the conflict stays offered and nothing is removed. */
internal fun cancelReinstall(state: MutableStateFlow<InstallUiState>) {
    state.update { it.copy(confirmingReinstall = false) }
}

/**
 * Removes the installed copy of the conflicting item, after the user confirmed. Checks again that
 * the item is ours and that the package is not a system app. True when the package is gone (or was
 * already), so the install can be tried again; otherwise the error is set.
 */
internal suspend fun uninstallConflict(
    state: MutableStateFlow<InstallUiState>,
    owns: (InstallItem) -> Boolean,
    kind: suspend (String) -> PackageKind,
    uninstall: suspend (String) -> UninstallResult,
    ioError: (IOException) -> Int,
): Boolean {
    val conflict = state.value.conflict ?: return false
    val index = state.value.items.indexOfFirst { it.outputId == conflict.outputId && it.packageName == conflict.packageName }
    if (index < 0 || !owns(state.value.items[index])) {
        state.update { it.copy(conflict = null, confirmingReinstall = false) }
        return false
    }
    val pkg = conflict.packageName
    state.update {
        it.copy(conflict = null, confirmingReinstall = false, errorRes = null, errorDetail = null)
            .updateItem(index) { i -> i.copy(status = CheckStatus.Running, progress = null) }
    }
    fun failed(res: Int, detail: String?): Boolean {
        state.update { it.updateItem(index) { i -> i.copy(status = CheckStatus.Failed, progress = null) } }
        fail(state, res, detail)
        return false
    }
    return try {
        when (kind(pkg)) {
            PackageKind.System -> failed(R.string.install_error_signature, null)
            PackageKind.Missing -> true
            PackageKind.User -> when (val result = uninstall(pkg)) {
                UninstallResult.Success -> true
                is UninstallResult.Failure -> failed(R.string.reinstall_error_uninstall, result.message)
            }
        }
    } catch (e: IOException) {
        failed(ioError(e), e.message)
    }
}

/**
 * The build files for the items still to install, or null (with the error set) when the last build
 * is missing, was made for another prefix, or a file is gone or changed.
 */
internal fun resolveBuildFiles(state: MutableStateFlow<InstallUiState>, built: BuiltBuild?, prefix: String): Map<String, File>? {
    if (built == null || built.prefix != prefix) {
        fail(state, R.string.install_error_build_missing)
        return null
    }
    val files = HashMap<String, File>()
    for (item in state.value.items.filter { it.status != CheckStatus.Passed }) {
        val record = built.outputs.firstOrNull { it.outputId == item.outputId }
        val file = record?.let { File(it.path) }
        if (record == null || file == null || !file.isFile || file.length() != record.sizeBytes) {
            fail(state, R.string.install_error_build_missing)
            return null
        }
        files[item.outputId] = file
    }
    return files
}

private val GRANT_DONE = setOf(CheckStatus.Passed, CheckStatus.Warning)

private fun failedStatus(row: GrantRow) = if (row.grant.optional) CheckStatus.Warning else CheckStatus.Failed

internal fun setGrant(state: MutableStateFlow<InstallUiState>, item: Int, grant: Int, status: CheckStatus) {
    state.update {
        it.updateItem(item) { i -> i.copy(grants = i.grants.mapIndexed { n, row -> if (n == grant) row.copy(status = status) else row }) }
    }
}

/**
 * Applies the grants of item [index] that did not pass yet through [apply] (an ADB shell on the
 * device). True when every required grant passed; otherwise the error is set. [ioError] picks the
 * message for a failed connection.
 */
internal suspend fun applyItemGrants(
    state: MutableStateFlow<InstallUiState>,
    index: Int,
    apply: suspend (String, List<AdbGrant>) -> GrantReport,
    ioError: (IOException) -> Int,
): Boolean {
    val item = state.value.items[index]
    val todo = item.grants.indices.filter { item.grants[it].status !in GRANT_DONE }
    if (todo.isEmpty()) return true

    // A grant the device layer cannot express counts as a failed one.
    val mapped = ArrayList<Pair<Int, AdbGrant>>()
    for (i in todo) {
        val row = item.grants[i]
        try {
            mapped += i to row.grant.toAdbGrant()
        } catch (e: IllegalArgumentException) {
            setGrant(state, index, i, failedStatus(row))
            if (!row.grant.optional) {
                fail(state, R.string.install_error_grant, e.message)
                return false
            }
        }
    }
    mapped.forEach { (i, _) -> setGrant(state, index, i, CheckStatus.Running) }
    val report = try {
        apply(item.packageName, mapped.map { it.second })
    } catch (e: IOException) {
        mapped.forEach { (i, _) -> setGrant(state, index, i, CheckStatus.Pending) }
        fail(state, ioError(e), e.message)
        return false
    } catch (e: IllegalArgumentException) {
        // A value the command builder refuses (nothing ran).
        mapped.forEach { (i, _) -> setGrant(state, index, i, CheckStatus.Failed) }
        fail(state, R.string.install_error_grant, e.message)
        return false
    }
    mapped.forEachIndexed { n, (i, _) ->
        val outcome = report.outcomes.getOrNull(n)
        val row = item.grants[i]
        setGrant(
            state, index, i,
            when {
                outcome == null -> CheckStatus.Pending
                outcome.applied -> CheckStatus.Passed
                else -> failedStatus(row)
            },
        )
    }
    val failed = report.failedRequired ?: return true
    fail(state, R.string.install_error_grant, failed.result.stderr.ifBlank { failed.result.stdout }.trim().take(200))
    return false
}
