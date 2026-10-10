package io.openshm.patcher.ui.screens.selftest

import android.content.Context
import io.openshm.patcher.ui.components.CheckStatus

/** The outcome of one finished self-test run: when it ended and how many rows ended in each state. */
data class SelfTestResult(val timestampMillis: Long, val passed: Int, val warnings: Int, val failed: Int) {
    /** Failed beats warning beats passed. */
    val overall: CheckStatus
        get() = when {
            failed > 0 -> CheckStatus.Failed
            warnings > 0 -> CheckStatus.Warning
            else -> CheckStatus.Passed
        }
}

/** The result of a finished run, or null while the state still has open rows. */
internal fun SelfTestUiState.result(nowMillis: Long): SelfTestResult? =
    if (!finished) null else SelfTestResult(nowMillis, passedCount, warningCount, failedCount)

/** Where the last [SelfTestResult] is kept so Home can show it. */
interface SelfTestRecord {
    fun last(): SelfTestResult?
    fun save(result: SelfTestResult)
}

/** SharedPreferences `selftest`: timestamp, the three counts and the overall status. */
class PrefsSelfTestRecord(context: Context) : SelfTestRecord {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun last(): SelfTestResult? {
        if (!prefs.contains(KEY_TIME)) return null
        return SelfTestResult(
            timestampMillis = prefs.getLong(KEY_TIME, 0L),
            passed = prefs.getInt(KEY_PASSED, 0),
            warnings = prefs.getInt(KEY_WARNINGS, 0),
            failed = prefs.getInt(KEY_FAILED, 0),
        )
    }

    override fun save(result: SelfTestResult) {
        prefs.edit()
            .putLong(KEY_TIME, result.timestampMillis)
            .putInt(KEY_PASSED, result.passed)
            .putInt(KEY_WARNINGS, result.warnings)
            .putInt(KEY_FAILED, result.failed)
            .putString(KEY_OVERALL, result.overall.name)
            .apply()
    }

    private companion object {
        const val PREFS = "selftest"
        const val KEY_TIME = "timestamp"
        const val KEY_PASSED = "passed"
        const val KEY_WARNINGS = "warnings"
        const val KEY_FAILED = "failed"
        const val KEY_OVERALL = "overall"
    }
}
