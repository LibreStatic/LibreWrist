package io.openshm.patcher.ui.screens.selftest

import io.openshm.patcher.ui.components.CheckStatus

/** What the shared report says about the build: versions and ids, nothing about the person or the devices. */
internal data class ReportHeader(
    val appVersion: String,
    val catalogueSerial: Long,
    /** One line per input: id, label and official version. */
    val inputs: List<String>,
    /** One line per output: id and package name. */
    val packages: List<String>,
)

private const val LOG_LINES = 40
private const val LOG_LINE_CHARS = 160

/**
 * The part of the build log worth sharing: the per-output headers and the failure lines, capped. The
 * whole log stays on the device.
 */
internal fun engineLogSummary(log: List<String>): List<String> {
    if (log.isEmpty()) return emptyList()
    val interesting = log.filter { it.startsWith("==") || it.contains("FAILED") || it.contains("error", ignoreCase = true) }
    return interesting.take(LOG_LINES).map { it.take(LOG_LINE_CHARS) }
}

/**
 * The plain-text report behind "Share report": app and catalogue versions, input versions, package
 * ids, one line per check with its result, and the engine log summary. By construction it has no
 * room for keys, certificates, device serials, account names, notification text or health data.
 */
internal fun buildSelfTestReport(
    header: ReportHeader,
    state: SelfTestUiState,
    sectionTitle: (Int) -> String,
    rowText: (SelfTestText) -> String,
    engineLog: List<String>,
): String = buildString {
    appendLine("LibreWrist self-test")
    appendLine("App: ${header.appVersion}")
    appendLine("Catalogue serial: ${header.catalogueSerial}")
    appendLine()
    appendLine("Inputs")
    header.inputs.forEach { appendLine("  $it") }
    appendLine("Packages")
    header.packages.forEach { appendLine("  $it") }
    appendLine()
    appendLine("Result: ${state.passedCount} passed, ${state.warningCount} warnings, ${state.failedCount} failed of ${state.totalCount}")
    for (section in state.sections) {
        appendLine()
        appendLine(sectionTitle(section.title))
        for (row in section.rows) {
            append("  [${label(row.status)}] ${rowText(row.title)}")
            row.detail?.let { append(" - ${rowText(it)}") }
            appendLine()
        }
    }
    appendLine()
    appendLine("Engine log")
    if (engineLog.isEmpty()) appendLine("  not available (no build in this session)") else engineLog.forEach { appendLine("  $it") }
}

private fun label(status: CheckStatus) = when (status) {
    CheckStatus.Passed -> "PASS"
    CheckStatus.Warning -> "WARN"
    CheckStatus.Failed -> "FAIL"
    CheckStatus.Running -> "RUN"
    CheckStatus.Pending -> "WAIT"
}
