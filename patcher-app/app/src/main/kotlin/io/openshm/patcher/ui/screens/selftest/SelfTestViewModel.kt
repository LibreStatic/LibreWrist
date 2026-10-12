package io.openshm.patcher.ui.screens.selftest

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.build.BuildController
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import android.util.Log
import io.openshm.patcher.device.AdbWatchDevice
import io.openshm.patcher.device.AndroidPhoneProbe
import io.openshm.patcher.device.PhoneGrants
import io.openshm.patcher.device.WatchLink
import io.openshm.patcher.device.toAdbGrant
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wires the real device, build and storage into [SelfTestRunner], starts a run when the screen opens
 * and keeps the last finished result for Home. The checks themselves are in the runner.
 */
class SelfTestViewModel(application: Application) : AndroidViewModel(application) {
    private val session = SetupSession.state.value
    private val catalogue = AppCatalogue.current
    private val record: SelfTestRecord = PrefsSelfTestRecord(application)

    private val runner = SelfTestRunner(
        catalogue = catalogue,
        outputIds = session.watchProfile.outputIds,
        prefix = session.packagePrefix,
        build = { BuildStorage(application).record.read() },
        signingCertSha256 = { signingCertificateOrNull(application) },
        packages = AndroidPackageInspector(application),
        hasher = FileSha256(),
        phoneGrants = PhoneGrants(AndroidPhoneProbe(application)),
        bridge = ContentResolverBridgeStatus(application),
        watch = LinkWatchShell(WatchLink.get(application)),
        io = Dispatchers.IO,
        logFailure = { message, e -> Log.w(TAG, message, e) },
    )

    private val _state = MutableStateFlow(runner.initial())
    val state: StateFlow<SelfTestUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var saved = false
    private var rerunOnResume = false

    init {
        start()
    }

    fun onEvent(event: SelfTestEvent) {
        when (event) {
            is SelfTestEvent.Answer -> {
                _state.update { it.answer(event.yes) }
                saveIfFinished()
            }
            SelfTestEvent.Rerun -> start()
            // Handled by the screen host: they need navigation and the activity.
            is SelfTestEvent.Fix, SelfTestEvent.Share -> Unit
        }
    }

    /**
     * Where the Fix button of [rowId] leads, or null. The screen is tested again when the user comes
     * back. Watch access is repaired here instead: the grants are applied again and the test reruns.
     */
    fun fixFor(rowId: String): SelfTestFix? {
        val row = _state.value.sections.flatMap { it.rows }.firstOrNull { it.id == rowId } ?: return null
        if (row.status != CheckStatus.Failed && row.status != CheckStatus.Warning) return null
        val fix = row.fix ?: return null
        if (fix == SelfTestFix.WatchGrants) {
            outputOfWatchGrantsRow(rowId)?.let { applyWatchGrants(rowId, it) }
            return null
        }
        rerunOnResume = true
        return fix
    }

    private fun applyWatchGrants(rowId: String, outputId: String) {
        val out = catalogue.outputs.firstOrNull { it.id == outputId } ?: return
        job?.cancel()
        _state.update { it.withRow(rowId) { row -> row.copy(status = CheckStatus.Running, detail = null) } }
        job = viewModelScope.launch {
            val pkg = out.packageName(session.packagePrefix)
            val grants = catalogue.grants[outputId].orEmpty().mapNotNull { grant ->
                try {
                    grant.toAdbGrant()
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            try {
                val report = AdbWatchDevice(WatchLink.get(getApplication())).applyGrants(pkg, grants)
                Log.i(TAG, "watch grants for $pkg: $report")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "watch grants for $pkg failed", e)
            }
            start()
        }
    }

    /** The screen is in front again; after a fix the checks run once more. */
    fun onResumed() {
        if (rerunOnResume) {
            rerunOnResume = false
            start()
        }
    }

    /** The text "Share report" sends: versions, ids, results and the engine log summary only. */
    fun reportText(): String {
        val app = getApplication<Application>()
        val header = ReportHeader(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            catalogueSerial = catalogue.serial,
            inputs = catalogue.inputs.filter { it.id in session.watchProfile.inputIds }.map { "${it.id} ${it.label} ${it.versionName}" },
            packages = catalogue.outputs.filter { it.id in session.watchProfile.outputIds }.map { "${it.id} ${it.packageName(session.packagePrefix)}" },
        )
        return buildSelfTestReport(
            header, _state.value,
            sectionTitle = { app.getString(it) },
            rowText = { app.getString(it.res, *it.args.toTypedArray()) },
            engineLog = engineLogSummary(BuildController.state.value.log),
        )
    }

    private fun start() {
        job?.cancel()
        saved = false
        _state.value = runner.initial()
        job = viewModelScope.launch {
            runner.run(_state)
            saveIfFinished()
        }
    }

    private fun saveIfFinished() {
        val result = _state.value.result(System.currentTimeMillis()) ?: return
        if (saved) return
        saved = true
        record.save(result)
    }

    private companion object {
        const val TAG = "SelfTest"
    }
}
