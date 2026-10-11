package io.openshm.patcher.build

import io.openshm.patcher.ui.screens.build.BuildStage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BuildRunPhase { Idle, Running, Succeeded, Failed, Cancelled }

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
    /** The user asked to stop; the build ends at the next engine event or between two outputs. */
    val cancelRequested: Boolean = false,
)

/**
 * The single, process-wide source of truth for the build. The service writes to it; view models only
 * read it, so a rotation or a screen leaving changes nothing. It lives in memory: if the process
 * dies, the state is [BuildRunPhase.Idle] again and a finished build is found in [BuiltOutputs].
 */
object BuildController {
    private val _state = MutableStateFlow(BuildRunState())
    val state: StateFlow<BuildRunState> = _state.asStateFlow()

    /** Milliseconds since an arbitrary origin; replaced in tests. */
    internal var clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    @Volatile private var startedAt = clockMillis()

    /** Moves to Running for [key] and returns true, or returns false (and changes nothing) when a build is running. */
    fun tryBegin(key: String): Boolean {
        while (true) {
            val current = _state.value
            if (current.phase == BuildRunPhase.Running) return false
            if (_state.compareAndSet(current, BuildRunState(BuildRunPhase.Running, key))) {
                startedAt = clockMillis()
                return true
            }
        }
    }

    fun onStage(outputId: String, index: Int, count: Int, stage: BuildStage) = _state.update {
        it.copy(
            outputId = outputId, outputIndex = index, outputCount = count, stage = stage,
            progress = maxOf(it.progress, progressOf(index, count, stage)),
        )
    }

    /** Appends [line] prefixed with the `mm:ss` elapsed since the build began. */
    fun onLog(line: String) {
        val seconds = ((clockMillis() - startedAt) / 1000).coerceAtLeast(0)
        val stamped = "%02d:%02d %s".format(seconds / 60, seconds % 60, line)
        _state.update { it.copy(log = it.log + stamped) }
    }

    /** Asks the running build to stop; true when there was one to ask. */
    fun requestCancel(): Boolean {
        while (true) {
            val current = _state.value
            if (current.phase != BuildRunPhase.Running) return false
            if (current.cancelRequested || _state.compareAndSet(current, current.copy(cancelRequested = true))) return true
        }
    }

    fun isCancelRequested(): Boolean = _state.value.cancelRequested

    fun cancelled() = _state.update { it.copy(phase = BuildRunPhase.Cancelled, stage = null) }

    /** Leaves a cancelled build behind, so opening the Build screen again starts a new one. */
    fun clearCancelled() {
        _state.update { if (it.phase == BuildRunPhase.Cancelled) BuildRunState() else it }
    }

    fun succeed() = _state.update { it.copy(phase = BuildRunPhase.Succeeded, stage = null, progress = 1f) }

    fun fail(message: String) = _state.update { it.copy(phase = BuildRunPhase.Failed, error = message) }

    /** Overall progress: finished outputs plus the share of the current one that its stage stands for. */
    internal fun progressOf(index: Int, count: Int, stage: BuildStage): Float =
        ((index + stage.ordinal.toFloat() / BuildStage.entries.size) / count.coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** Identifies what a build is for: the same key means the same inputs, prefix, outputs and skipped patches, so a finished build is still good. */
fun buildKey(catalogueSerial: Long, prefix: String, outputIds: Collection<String>, disabledPatches: Collection<String> = emptySet()): String =
    "$catalogueSerial|$prefix|${outputIds.sorted().joinToString(",")}|${disabledPatches.sorted().joinToString(",")}"
