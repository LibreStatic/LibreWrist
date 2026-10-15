package io.openshm.patcher.ui.screens.revert

import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp

/** What a revert run covers: the whole setup, everything on the watch, or a single installed app. */
sealed interface RevertScope {
    /** "Undo all changes": both devices. */
    data object All : RevertScope

    /** "Restore the watch": every app and setting the setup left on the watch; the phone stays as it is. */
    data object Watch : RevertScope

    /** One output of the catalogue (a Home build row), on its own device. */
    data class Output(val outputId: String) : RevertScope

    /** The navigation argument value: `all`, `watch` or `output:<id>`. */
    val encoded: String
        get() = when (this) {
            All -> ALL
            Watch -> WATCH
            is Output -> "$OUTPUT_PREFIX$outputId"
        }

    /** Whether the watch takes part in the run; when it does not, it is never contacted nor asked about. */
    fun involvesWatch(outputs: List<OutputApp>): Boolean = when (this) {
        All, Watch -> true
        is Output -> outputs.firstOrNull { it.id == outputId }?.device == Device.Watch
    }

    /** Whether the phone takes part in the run. */
    fun involvesPhone(outputs: List<OutputApp>): Boolean = when (this) {
        All -> true
        Watch -> false
        is Output -> outputs.firstOrNull { it.id == outputId }?.device != Device.Watch
    }

    /** Whether the stock-app, always-on and hidden-API entries of the watch belong to the run. */
    val includesWatchExtras: Boolean
        get() = when (this) {
            All, Watch -> true
            is Output -> outputId == RevertPlan.SHM_WATCH_OUTPUT
        }

    /** Whether the candidate package is part of the run. */
    fun includes(candidate: RevertCandidate): Boolean = when (this) {
        All -> true
        Watch -> candidate.device == Device.Watch
        is Output -> candidate.outputId == outputId
    }

    companion object {
        private const val ALL = "all"
        private const val WATCH = "watch"
        private const val OUTPUT_PREFIX = "output:"

        /** The scope for a navigation argument; anything unknown or missing means [All]. */
        fun parse(value: String?): RevertScope = when {
            value == WATCH -> Watch
            value != null && value.startsWith(OUTPUT_PREFIX) && value.length > OUTPUT_PREFIX.length ->
                Output(value.removePrefix(OUTPUT_PREFIX))
            else -> All
        }
    }
}
