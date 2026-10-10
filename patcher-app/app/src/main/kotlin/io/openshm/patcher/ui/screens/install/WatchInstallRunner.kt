package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.InstallResult
import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.device.WatchDevice
import io.openshm.patcher.device.toAdbGrant
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
 * Nothing is ever uninstalled. A run can be started again after a stop: items that are installed
 * are not sent again, and only grants that did not pass are applied again.
 */
internal class WatchInstallRunner(
    private val device: WatchDevice,
    private val build: () -> BuiltBuild?,
    private val prefix: () -> String,
    private val lockPrefix: () -> Unit,
) {
    private var locked = false

    suspend fun run(state: MutableStateFlow<InstallUiState>) {
        locked = false
        state.update { it.copy(running = true, errorRes = null, errorDetail = null) }
        try {
            walk(state)
        } finally {
            state.update { it.copy(running = false) }
        }
    }

    private suspend fun walk(state: MutableStateFlow<InstallUiState>) {
        val files = resolveFiles(state) ?: return
        for (index in state.value.items.indices) {
            val item = state.value.items[index]
            if (item.status != CheckStatus.Passed) {
                if (!install(state, index, files.getValue(item.outputId))) return
            }
            if (!grant(state, index)) return
        }
    }

    /** The build files for the items still to install, or null (with the error set) when they cannot be used. */
    private fun resolveFiles(state: MutableStateFlow<InstallUiState>): Map<String, File>? {
        val built = build()
        if (built == null || built.prefix != prefix()) {
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

    private fun fail(state: MutableStateFlow<InstallUiState>, @StringRes res: Int, detail: String? = null) {
        state.update { it.copy(errorRes = res, errorDetail = detail) }
    }

    /** True when the package is installed. */
    private suspend fun install(state: MutableStateFlow<InstallUiState>, index: Int, file: File): Boolean {
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
                val res = if (result.code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                    R.string.install_error_signature
                } else {
                    R.string.install_error_failed
                }
                fail(state, res, listOfNotNull(result.code, result.message).distinct().joinToString(": "))
                false
            }
        }
    }

    /** True when every required grant of the item passed. */
    private suspend fun grant(state: MutableStateFlow<InstallUiState>, index: Int): Boolean {
        val item = state.value.items[index]
        val todo = item.grants.indices.filter { item.grants[it].status !in DONE }
        if (todo.isEmpty()) return true

        // A grant the device layer cannot express counts as a failed one.
        val mapped = ArrayList<Pair<Int, io.openshm.patcher.adb.Grant>>()
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
            device.applyGrants(item.packageName, mapped.map { it.second })
        } catch (e: IOException) {
            mapped.forEach { (i, _) -> setGrant(state, index, i, CheckStatus.Pending) }
            fail(state, if (e is AdbException) watchErrorRes(e) else R.string.install_error_grant, e.message)
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

    private fun failedStatus(row: GrantRow) = if (row.grant.optional) CheckStatus.Warning else CheckStatus.Failed

    private fun setGrant(state: MutableStateFlow<InstallUiState>, item: Int, grant: Int, status: CheckStatus) {
        state.update {
            it.updateItem(item) { i -> i.copy(grants = i.grants.mapIndexed { n, row -> if (n == grant) row.copy(status = status) else row }) }
        }
    }

    private companion object {
        val DONE = setOf(CheckStatus.Passed, CheckStatus.Warning)
    }
}

private fun InstallUiState.updateItem(index: Int, change: (InstallItem) -> InstallItem) =
    copy(items = items.mapIndexed { i, item -> if (i == index) change(item) else item })
