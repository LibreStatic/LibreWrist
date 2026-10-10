package io.openshm.patcher.ui.screens.install

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.adb.Grant as AdbGrant
import io.openshm.patcher.adb.GrantReport
import io.openshm.patcher.build.BuiltBuild
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

/** The message for a refused install; a copy with another signature is called out, nothing is ever removed. */
@StringRes
internal fun installFailureRes(code: String?): Int =
    if (code == "INSTALL_FAILED_UPDATE_INCOMPATIBLE") R.string.install_error_signature else R.string.install_error_failed

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
