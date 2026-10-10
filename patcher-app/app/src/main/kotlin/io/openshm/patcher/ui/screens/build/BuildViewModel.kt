package io.openshm.patcher.ui.screens.build

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SetupSession
import io.openshm.patcher.ui.components.CheckStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

internal fun builtOutputs(): BuildDoneUiState {
    val session = SetupSession.state.value
    val profile = session.watchProfile
    return BuildDoneUiState(
        outputs = AppCatalogue.current.outputs.filter { it.id in profile.outputIds }.map {
            BuiltOutput(it.id, it.label, it.device, it.packageName(session.packagePrefix), sizeBytes = 0L)
        },
        patchCount = AppCatalogue.current.patternCount(profile.inputIds),
    )
}

/**
 * Shell-stage view model: walks the stages on a timer. The engine run (unpack, fingerprint check,
 * patch, rename, rebuild, sign, self-check) replaces [run] and reports the same [BuildStage]s.
 */
class BuildViewModel : ViewModel() {
    private val patchCount = AppCatalogue.current.patternCount(SetupSession.state.value.watchProfile.inputIds)
    private val _state = MutableStateFlow(BuildUiState(patchCount = patchCount))
    val state: StateFlow<BuildUiState> = _state.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    init {
        run()
    }

    fun onEvent(event: BuildEvent) {
        when (event) {
            BuildEvent.ShowLog -> _state.update { it.copy(logVisible = true) }
            BuildEvent.HideLog -> _state.update { it.copy(logVisible = false) }
            BuildEvent.Retry -> run()
        }
    }

    private fun run() {
        viewModelScope.launch {
            _finished.value = false
            for (stage in BuildStage.entries) {
                _state.update { it.startStage(stage).copy(log = it.log + "start ${stage.name.lowercase()}") }
                delay(STAGE_DELAY_MS)
            }
            _state.update { it.finish().copy(log = it.log + "done") }
            _finished.value = true
        }
    }

    private companion object {
        const val STAGE_DELAY_MS = 700L
    }
}
