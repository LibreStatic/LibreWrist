package io.openshm.patcher.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One per-app rebuild started from Home. [requested] is what the user asked for (the outputs of one
 * input, or a single bridge); [rebuilt] are the other outputs a build of this trip patched again
 * instead of reusing (an option group shared with the requested app, a new prefix or engine), which
 * must be installed too.
 */
data class RebuildState(val requested: Set<String>, val rebuilt: Set<String> = emptySet()) {
    /** The outputs this rebuild shows and installs. */
    val scope: Set<String> get() = requested + rebuilt
}

/**
 * In-memory state of the rebuild in progress, if any. Not persisted: it lives for one trip from Home
 * through the guided steps and back, and Home clears it on arrival ([clear]). The build itself is the
 * normal full build, which reuses every unchanged app; this only narrows what follows it.
 */
object RebuildScope {
    private val _state = MutableStateFlow<RebuildState?>(null)
    val state: StateFlow<RebuildState?> = _state.asStateFlow()

    @Synchronized
    fun begin(requested: Set<String>) {
        _state.value = RebuildState(requested)
    }

    /** Adds the outputs a finished build patched again; kept for the whole trip, since they are not installed yet. */
    @Synchronized
    fun addRebuilt(outputIds: Collection<String>) {
        _state.value = _state.value?.let { it.copy(rebuilt = it.rebuilt + outputIds) }
    }

    @Synchronized
    fun clear() {
        _state.value = null
    }

    /** The outputs in play, or null outside a rebuild. */
    val scope: Set<String>? get() = _state.value?.scope

    /** Whether [outputId] is part of this run; true for every output outside a rebuild. */
    fun includes(outputId: String): Boolean = scope?.contains(outputId) ?: true
}

/**
 * The outputs a rebuild of [outputId] covers: the session outputs built from the same input in the given
 * [chinaMode]; a bridge has no input and stands alone.
 */
fun requestedRebuildScope(
    outputs: List<OutputApp>, outputId: String, sessionOutputIds: Collection<String>, chinaMode: Boolean = false,
): Set<String> {
    val inputId = outputs.firstOrNull { it.id == outputId }?.sourceInputId(chinaMode) ?: return setOf(outputId)
    return outputs.filter { it.sourceInputId(chinaMode) == inputId && it.id in sessionOutputIds }.mapTo(linkedSetOf()) { it.id } + outputId
}
