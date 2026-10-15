package io.openshm.patcher.ui.screens.revert

import androidx.annotation.StringRes
import io.openshm.patcher.data.Device

/** Probing -> Review -> Running -> Done; "check again" and "retry the watch" go back to Probing. */
enum class RevertPhase { Probing, Review, Running, Done }

/** How the watch stands in this run; [NotNeeded] when the scope is a phone app only. */
enum class WatchStatus { Reached, Unreachable, Skipped, NotNeeded }

/** One entry the revert acts on, with its outcome once it ran. */
data class RevertRow(val item: RevertItem, val outcome: StepOutcome? = null)

/** What happened to the patcher's own uninstall request, when it did not remove the app. */
sealed interface SelfUninstallResult {
    data object Cancelled : SelfUninstallResult
    data class Failed(val message: String) : SelfUninstallResult
}

data class RevertUiState(
    /** What this run covers; it decides the wording and which options are offered. */
    val scope: RevertScope = RevertScope.All,
    val phase: RevertPhase = RevertPhase.Probing,
    /** The entries with something to undo, watch first. */
    val rows: List<RevertRow> = emptyList(),
    /** Apps looked for and not installed: summarised, not listed as work. */
    val notInstalled: List<RevertItem> = emptyList(),
    /** System apps with one of our package names: never touched. */
    val systemApps: List<RevertItem> = emptyList(),
    val watch: WatchStatus = WatchStatus.Reached,
    @StringRes val watchErrorRes: Int? = null,
    /** The "are you sure" dialog of the revert. */
    val confirming: Boolean = false,
    /** True when nothing is left on either device (only meaningful in [RevertPhase.Done]). */
    val complete: Boolean = false,
    val confirmingSelfUninstall: Boolean = false,
    val selfUninstall: SelfUninstallResult? = null,
    /** Set by the view model once a scoped run left no active output, so the whole setup was forgotten. */
    val setupForgotten: Boolean = false,
) {
    /** Leaving the watch alone is only possible for the whole setup: in a watch scope it would leave nothing to do. */
    val canSkipWatch: Boolean get() = scope == RevertScope.All

    val canRevert: Boolean get() = phase == RevertPhase.Review && rows.isNotEmpty() && watch != WatchStatus.Unreachable
    val partial: Boolean get() = phase == RevertPhase.Done && !complete
    val watchNeedsDecision: Boolean get() = phase == RevertPhase.Review && watch == WatchStatus.Unreachable
}

sealed interface RevertEvent {
    data class Probed(val probe: RevertProbe, @StringRes val watchErrorRes: Int?) : RevertEvent

    /** Check the devices again ("Try the watch again", "Check again"). */
    data object Reprobe : RevertEvent
    data object SkipWatch : RevertEvent
    data object AskRevert : RevertEvent
    data object DismissRevert : RevertEvent
    data object Start : RevertEvent
    data class Step(val key: String, val outcome: StepOutcome) : RevertEvent
    data class Finished(val report: RevertReport) : RevertEvent
    data object AskSelfUninstall : RevertEvent
    data object DismissSelfUninstall : RevertEvent
    data object ConfirmSelfUninstall : RevertEvent
    data class SelfUninstallEnded(val result: SelfUninstallResult) : RevertEvent
}

internal fun RevertUiState.reduce(event: RevertEvent): RevertUiState = when (event) {
    is RevertEvent.Probed -> {
        val items = event.probe.items
        val status = when (event.probe.watch) {
            WatchReach.Reached -> WatchStatus.Reached
            is WatchReach.Unreachable -> WatchStatus.Unreachable
            WatchReach.Skipped -> WatchStatus.Skipped
            WatchReach.NotNeeded -> WatchStatus.NotNeeded
        }
        val rows = items.filter { it.actionable }.map { RevertRow(it) }
        copy(
            phase = RevertPhase.Review,
            rows = rows,
            notInstalled = items.filter { it.presence == Presence.Absent && it.action == RevertAction.Uninstall },
            systemApps = items.filter { it.presence == Presence.System },
            watch = status,
            watchErrorRes = event.watchErrorRes.takeIf { status == WatchStatus.Unreachable },
            confirming = false,
            selfUninstall = null,
        ).finishIfNothingToDo()
    }
    RevertEvent.Reprobe -> copy(phase = RevertPhase.Probing, confirming = false, selfUninstall = null)
    RevertEvent.SkipWatch -> if (!canSkipWatch) this else copy(
        watch = WatchStatus.Skipped,
        watchErrorRes = null,
        rows = rows.filter { it.item.device != Device.Watch },
    ).finishIfNothingToDo()
    RevertEvent.AskRevert -> if (canRevert) copy(confirming = true) else this
    RevertEvent.DismissRevert -> copy(confirming = false)
    RevertEvent.Start -> if (phase == RevertPhase.Review) {
        copy(phase = RevertPhase.Running, confirming = false, rows = rows.map { it.copy(outcome = null) })
    } else this
    is RevertEvent.Step -> copy(rows = rows.map { if (it.item.key == event.key) it.copy(outcome = event.outcome) else it })
    is RevertEvent.Finished -> copy(
        phase = RevertPhase.Done,
        complete = event.report.complete,
        watch = if (watch == WatchStatus.Reached && !event.report.watchCovered) WatchStatus.Unreachable else watch,
    )
    RevertEvent.AskSelfUninstall -> if (phase == RevertPhase.Done) copy(confirmingSelfUninstall = true, selfUninstall = null) else this
    RevertEvent.DismissSelfUninstall -> copy(confirmingSelfUninstall = false)
    RevertEvent.ConfirmSelfUninstall -> copy(confirmingSelfUninstall = false)
    is RevertEvent.SelfUninstallEnded -> copy(selfUninstall = event.result)
}

/**
 * Nothing to undo and the watch is not waiting for a decision: there is no review to show. The
 * revert counts as complete only when the watch was actually checked.
 */
private fun RevertUiState.finishIfNothingToDo(): RevertUiState =
    if (rows.isEmpty() && watch != WatchStatus.Unreachable) {
        copy(phase = RevertPhase.Done, complete = watch == WatchStatus.Reached || watch == WatchStatus.NotNeeded)
    } else this
