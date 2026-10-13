package io.openshm.patcher.ui.screens.build

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.build.BuildController
import io.openshm.patcher.build.BuildRunPhase
import io.openshm.patcher.build.BuildRunState
import io.openshm.patcher.build.BuildService
import io.openshm.patcher.build.BuildStorage
import io.openshm.patcher.build.buildKey
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Marks [stage] as running and every earlier stage as passed. */
internal fun BuildUiState.startStage(stage: BuildStage): BuildUiState = copy(
    stages = stages.map { row ->
        when {
            row.stage.ordinal < stage.ordinal -> row.copy(status = CheckStatus.Passed)
            row.stage == stage -> row.copy(status = CheckStatus.Running)
            else -> row
        }
    },
)

internal fun BuildUiState.finish(): BuildUiState = copy(stages = stages.map { it.copy(status = CheckStatus.Passed) })

/** The screen's view of the controller state; [logVisible] is the only state the screen owns. */
internal fun BuildRunState.toUiState(patchCount: Int, logVisible: Boolean, currentApp: CurrentApp? = null): BuildUiState {
    val base = BuildUiState(
        patchCount = patchCount, log = log, logVisible = logVisible, overall = progress,
        running = phase == BuildRunPhase.Running, cancelRequested = cancelRequested, currentApp = currentApp,
    )
    return when (phase) {
        BuildRunPhase.Cancelled -> base.copy(
            cancelled = true,
            stages = base.stages.map { row ->
                when {
                    stage == null -> row
                    row.stage.ordinal < stage.ordinal -> row.copy(status = CheckStatus.Passed)
                    else -> row
                }
            },
        )
        BuildRunPhase.Succeeded -> base.finish()
        BuildRunPhase.Failed -> base.copy(
            failed = true,
            error = error,
            stages = base.stages.map { row ->
                when {
                    stage == null -> row
                    row.stage.ordinal < stage.ordinal -> row.copy(status = CheckStatus.Passed)
                    row.stage == stage -> row.copy(status = CheckStatus.Failed)
                    else -> row
                }
            },
        )
        else -> stage?.let { base.startStage(it) } ?: base
    }
}

/** The finished build for the current setup, read from the record the build service writes. */
internal fun builtOutputs(storage: BuildStorage): BuildDoneUiState {
    val session = SetupSession.state.value
    val sizes = storage.record.read()?.outputs?.associate { it.outputId to it.sizeBytes }.orEmpty()
    return BuildDoneUiState(
        outputs = AppCatalogue.current.outputs.filter { it.id in session.outputIds }.map {
            BuiltOutput(it.id, it.label, it.device, it.packageName(session.packagePrefix), sizeBytes = sizes[it.id] ?: 0L)
        },
        patchCount = AppCatalogue.current.patternCount(session.inputIds, session.disabledPatches),
    )
}

/**
 * Shows the build that [BuildController] reports. The build itself runs in [BuildService], so leaving
 * the screen or rotating changes nothing; [start] asks for a build unless one is running or a finished
 * one for the same inputs and prefix exists.
 */
class BuildViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = BuildStorage(application)
    private val patchCount = SetupSession.state.value.let { AppCatalogue.current.patternCount(it.inputIds, it.disabledPatches) }
    private val key = SetupSession.state.value.let {
        buildKey(AppCatalogue.current.serial, it.packagePrefix, it.outputIds, AppCatalogue.current.effectiveDisabled(it.disabledPatches))
    }
    private val logVisible = MutableStateFlow(false)

    /** Loads the app icons for the "current app" card; swapped for a fake in tests and previews. */
    val icons: AppIconLoader = ApkIconLoader(application)

    private fun currentApp(run: BuildRunState): CurrentApp? {
        val id = run.outputId ?: return null
        if (run.phase != BuildRunPhase.Running && run.phase != BuildRunPhase.Cancelled) return null
        val output = AppCatalogue.current.output(id)
        return CurrentApp(id, output.label, output.packageName(SetupSession.state.value.packagePrefix), output.device, run.outputIndex + 1, run.outputCount)
    }
    private val recordedFinished = MutableStateFlow(false)

    val state: StateFlow<BuildUiState> = combine(BuildController.state, logVisible) { run, visible ->
        run.toUiState(patchCount, visible, currentApp(run))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BuildUiState(patchCount = patchCount))

    val finished: StateFlow<Boolean> = combine(BuildController.state, recordedFinished) { run, recorded ->
        if (run.phase == BuildRunPhase.Running || run.phase == BuildRunPhase.Cancelled) false else (run.phase == BuildRunPhase.Succeeded && run.key == key) || recorded
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onEvent(event: BuildEvent) {
        when (event) {
            BuildEvent.ShowLog -> logVisible.update { true }
            BuildEvent.HideLog -> logVisible.update { false }
            BuildEvent.Retry -> BuildService.start(getApplication())
            BuildEvent.Cancel -> BuildController.requestCancel()
            BuildEvent.Dismiss -> BuildController.clearCancelled()
        }
    }

    /** Idempotent: safe to call again after a rotation or a permission result. */
    fun start() {
        val run = BuildController.state.value
        when {
            run.phase == BuildRunPhase.Running -> Unit
            run.key == key && run.phase != BuildRunPhase.Idle -> Unit // finished (or failed: Retry restarts) for this setup
            storage.record.read()?.key == key -> recordedFinished.value = true
            else -> BuildService.start(getApplication())
        }
    }
}
