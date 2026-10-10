package io.openshm.patcher.build

import io.openshm.patcher.ui.screens.build.BuildStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BuildRunPhase { Idle, Running, Succeeded, Failed }

/**
 * What the screens see of a build. [key] identifies what is being built (see `BuildKey`); [stage] is
 * the stage of the output being built ([outputIndex] of [outputCount]); [progress] is overall and
 * monotonic.
 */
data class BuildRunState(
    val phase: BuildRunPhase = BuildRunPhase.Idle,
    val key: String = "",
    val outputId: String? = null,
    val outputIndex: Int = 0,
    val outputCount: Int = 0,
    val stage: BuildStage? = null,
    val progress: Float = 0f,
    val log: List<String> = emptyList(),
    val error: String? = null,
)

/**
 * The single, process-wide source of truth for the build. The service writes to it; view models only
 * read it, so a rotation or a screen leaving changes nothing. It lives in memory: if the process
 * dies, the state is [BuildRunPhase.Idle] again and a finished build is found in [BuiltOutputs].
 */
object BuildController {
    private val _state = MutableStateFlow(BuildRunState())
    val state: StateFlow<BuildRunState> = _state.asStateFlow()

    /** Moves to Running for [key] and returns true, or returns false (and changes nothing) when a build is running. */
    fun tryBegin(key: String): Boolean {
        while (true) {
            val current = _state.value
            if (current.phase == BuildRunPhase.Running) return false
            if (_state.compareAndSet(current, BuildRunState(BuildRunPhase.Running, key))) return true
        }
    }

    fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) = _state.update {
        it.copy(
            outputId = outputId, outputIndex = index, outputCount = count, stage = stage,
            progress = maxOf(it.progress, progressOf(index, count, stage)),
        )
    }

    fun onLog(line: String) = _state.update { it.copy(log = it.log + line) }

    fun succeed() = _state.update { it.copy(phase = BuildRunPhase.Succeeded, stage = null, progress = 1f) }

    fun fail(message: String) = _state.update { it.copy(phase = BuildRunPhase.Failed, error = message) }

    /** Overall progress: finished outputs plus the share of the current one that its stage stands for. */
    internal fun progressOf(index: Int, count: Int, stage: BuildStage): Float =
        ((index + stage.ordinal.toFloat() / BuildStage.entries.size) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** Identifies what a build is for: the same key means the same inputs, prefix and outputs, so a finished build is still good. */
fun buildKey(catalogueSerial: Long, prefix: String, outputIds: Collection<String>): String =
    "$catalogueSerial|$prefix|${outputIds.sorted().joinToString(",")}"
