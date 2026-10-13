package io.openshm.patcher.ui.screens.revert

import io.openshm.patcher.adb.AdbException
import io.openshm.patcher.adb.GrantReadback
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.data.StockApp
import io.openshm.patcher.device.PackageKind
import io.openshm.patcher.device.PhoneInstaller
import io.openshm.patcher.device.UninstallResult
import io.openshm.patcher.device.WatchDevice
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Whether the watch could be asked about the revert. */
sealed interface WatchReach {
    data object Reached : WatchReach

    /** The watch did not answer (not paired, Wi-Fi, Wireless debugging off): its part is left for later. */
    data class Unreachable(val cause: IOException) : WatchReach

    /** The user chose to leave the watch alone. */
    data object Skipped : WatchReach
}

/** What the probe found: the entries to show and whether the watch answered. */
data class RevertProbe(val items: List<RevertItem>, val watch: WatchReach)

enum class SkipReason { NotInstalled, SystemApp, NothingToDo }

/** How one step ended. A user cancelling the phone's removal prompt is [Cancelled], an outcome, not an error. */
sealed interface StepOutcome {
    data object Running : StepOutcome
    data object Done : StepOutcome
    data class Skipped(val reason: SkipReason) : StepOutcome
    data object Cancelled : StepOutcome
    data class Failed(val message: String) : StepOutcome

    /** The watch stopped answering before this step ran. */
    data class WatchUnreachable(val message: String) : StepOutcome
}

/** A step that left nothing behind: it worked, or there was nothing to do or it must not be touched. */
val StepOutcome.settled: Boolean get() = this is StepOutcome.Done || this is StepOutcome.Skipped

/**
 * [complete] is true only when the watch was covered and every step settled: nothing is left on either
 * device, so the setup state can be forgotten.
 */
data class RevertReport(val outcomes: Map<String, StepOutcome>, val watchCovered: Boolean) {
    val complete: Boolean get() = watchCovered && outcomes.values.all { it.settled }
}

/**
 * Finds what the setup left on each device and undoes it, against the [PhoneInstaller] and
 * [WatchDevice] interfaces. Nothing is thrown out of [probe] or [run]: every exception becomes a
 * per-step outcome (or marks the watch as unreachable), and only cancellation propagates.
 * System packages are never removed, and neither is [ownPackage].
 */
class RevertRunner(
    private val phone: PhoneInstaller,
    private val watch: WatchDevice,
    private val outputs: List<OutputApp>,
    private val stockApps: List<StockApp>,
    private val storedPrefix: String?,
    private val ownPackage: String,
    private val log: (String) -> Unit = {},
) {
    private sealed interface Call<out T> {
        class Ok<T>(val value: T) : Call<T>
        class Unreachable(val cause: IOException) : Call<Nothing>
        class Error(val message: String) : Call<Nothing>
    }

    private suspend fun <T> guarded(block: suspend () -> T): Call<T> = try {
        Call.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbException.CommandFailed) {
        Call.Error(messageOf(e))
    } catch (e: IOException) {
        Call.Unreachable(e)
    } catch (e: Exception) {
        Call.Error(messageOf(e))
    }

    /**
     * Asks both devices what is there. With [includeWatch] false the watch is not contacted at all
     * (the user skipped it). An unreachable watch leaves its entries out and the phone's in.
     */
    suspend fun probe(includeWatch: Boolean = true): RevertProbe {
        var reach: WatchReach = WatchReach.Skipped
        var watchPackages: List<String> = emptyList()
        if (includeWatch) {
            when (val listed = guarded { watch.listPackages() }) {
                is Call.Ok -> { watchPackages = listed.value; reach = WatchReach.Reached }
                is Call.Unreachable -> reach = WatchReach.Unreachable(listed.cause)
                is Call.Error -> reach = WatchReach.Unreachable(IOException(listed.message))
            }
        }
        val listeners = when (val r = guarded { phone.notificationListenerPackages() }) {
            is Call.Ok -> r.value
            else -> emptyList()
        }
        val prefixes = RevertPlan.prefixes(storedPrefix, outputs, watchPackages, listeners)
        val candidates = RevertPlan.candidates(outputs, prefixes, exclude = setOf(ownPackage))

        val items = ArrayList<RevertItem>()
        for (c in candidates.filter { it.device == Device.Phone }) {
            val presence = when (val r = guarded { phone.packageKind(c.pkg) }) {
                is Call.Ok -> presenceOf(r.value)
                else -> Presence.Unknown
            }
            items += RevertItem(Device.Phone, c.label, c.pkg, RevertAction.Uninstall, presence)
        }
        if (reach == WatchReach.Reached) {
            val watchItems = probeWatch(candidates.filter { it.device == Device.Watch })
            if (watchItems == null) reach = WatchReach.Unreachable(IOException("The watch stopped answering"))
            else items += watchItems
        }
        // Watch entries first, as the run goes.
        return RevertProbe(items.sortedBy { if (it.device == Device.Watch) 0 else 1 }, reach)
    }

    /** The watch's entries, or null when it stopped answering part way. */
    private suspend fun probeWatch(candidates: List<RevertCandidate>): List<RevertItem>? {
        val items = ArrayList<RevertItem>()
        for (c in candidates) {
            val presence = when (val r = guarded { watch.packageKind(c.pkg) }) {
                is Call.Ok -> presenceOf(r.value)
                is Call.Unreachable -> return null
                is Call.Error -> Presence.Unknown
            }
            items += RevertItem(Device.Watch, c.label, c.pkg, RevertAction.Uninstall, presence)
        }
        for (stock in stockApps.filter { it.device == Device.Watch }) {
            val disabled = when (val r = guarded { watch.isDisabled(stock.packageName) }) {
                is Call.Ok -> r.value
                is Call.Unreachable -> return null
                is Call.Error -> false
            }
            items += RevertItem(
                Device.Watch, stock.label, stock.packageName, RevertAction.Enable,
                if (disabled) Presence.Present else Presence.Absent,
            )
        }
        val current = when (val r = guarded { watch.secureSetting(RevertPlan.ALWAYS_ON_KEY) }) {
            is Call.Ok -> r.value
            is Call.Unreachable -> return null
            is Call.Error -> null
        }
        items += RevertItem(
            Device.Watch, RevertPlan.ALWAYS_ON_KEY, RevertPlan.ALWAYS_ON_KEY, RevertAction.RestoreSetting,
            if (current == "0") Presence.Present else Presence.Absent,
        )
        val exemptions = when (val r = guarded { watch.globalSetting(RevertPlan.HIDDEN_API_KEY) }) {
            is Call.Ok ->
                if (GrantReadback.listContainsEntry(r.value, RevertPlan.HIDDEN_API_ENTRY)) Presence.Present else Presence.Absent
            is Call.Unreachable -> return null
            is Call.Error -> Presence.Unknown
        }
        items += RevertItem(
            Device.Watch, RevertPlan.HIDDEN_API_KEY, RevertPlan.HIDDEN_API_KEY, RevertAction.RemoveExemption, exemptions,
        )
        return items
    }

    private fun presenceOf(kind: PackageKind) = when (kind) {
        PackageKind.Missing -> Presence.Absent
        PackageKind.System -> Presence.System
        PackageKind.User -> Presence.Present
    }

    /**
     * Acts on the actionable [items]: the watch first (it needs the patcher alive), then the phone;
     * on each device removals, then re-enabling, then the settings. Each step re-checks the device
     * right before acting, so a package that changed since the probe is handled. [watchCovered] is
     * false when the watch was unreachable or skipped, which keeps the result partial.
     */
    suspend fun run(items: List<RevertItem>, watchCovered: Boolean, onStep: (RevertItem, StepOutcome) -> Unit): RevertReport {
        val outcomes = LinkedHashMap<String, StepOutcome>()
        var watchDown: String? = null
        val ordered = items.filter { it.actionable }
            .sortedWith(compareBy({ if (it.device == Device.Watch) 0 else 1 }, { it.action.ordinal }))
        for (item in ordered) {
            onStep(item, StepOutcome.Running)
            val down = watchDown
            val outcome = if (item.device == Device.Watch && down != null) {
                StepOutcome.WatchUnreachable(down)
            } else {
                execute(item) { watchDown = it.message ?: it.javaClass.simpleName }
            }
            log("revert ${item.device} ${item.action} ${item.pkg}: ${outcome::class.simpleName}")
            outcomes[item.key] = outcome
            onStep(item, outcome)
        }
        return RevertReport(outcomes, watchCovered && watchDown == null)
    }

    private class Down(val reason: IOException) : Exception(reason)
    private class Fail(message: String) : Exception(message)

    private fun <T> Call<T>.get(): T = when (this) {
        is Call.Ok -> value
        is Call.Unreachable -> throw Down(cause)
        is Call.Error -> throw Fail(message)
    }

    /** Never throws (but cancellation): a lost watch is reported through [onWatchDown], anything else is a failed step. */
    private suspend fun execute(item: RevertItem, onWatchDown: (IOException) -> Unit): StepOutcome = try {
        when (item.action) {
            RevertAction.Uninstall -> uninstall(item)
            RevertAction.Enable -> enable(item)
            RevertAction.RestoreSetting -> restore(item)
            RevertAction.RemoveExemption -> removeExemption(item)
        }
    } catch (e: Down) {
        if (item.device == Device.Watch) {
            onWatchDown(e.reason)
            StepOutcome.WatchUnreachable(messageOf(e.reason))
        } else {
            StepOutcome.Failed(messageOf(e.reason)) // the phone's own installer has no link to lose
        }
    } catch (e: Fail) {
        StepOutcome.Failed(e.message.orEmpty())
    }

    private suspend fun uninstall(item: RevertItem): StepOutcome {
        if (item.pkg == ownPackage) return StepOutcome.Skipped(SkipReason.NothingToDo)
        val onWatch = item.device == Device.Watch
        // Asked again right before acting: the probe may be old.
        val kind = guarded { if (onWatch) watch.packageKind(item.pkg) else phone.packageKind(item.pkg) }.get()
        when (kind) {
            PackageKind.Missing -> return StepOutcome.Skipped(SkipReason.NotInstalled)
            PackageKind.System -> return StepOutcome.Skipped(SkipReason.SystemApp)
            PackageKind.User -> Unit
        }
        if (onWatch) dropCompanionAssociations(item.pkg)
        return when (val result = guarded { if (onWatch) watch.uninstall(item.pkg) else phone.uninstall(item.pkg) }.get()) {
            UninstallResult.Success -> StepOutcome.Done
            is UninstallResult.Failure ->
                if (result.message.startsWith(ABORTED)) StepOutcome.Cancelled else StepOutcome.Failed(result.message)
        }
    }

    /**
     * Removes the watch's companion associations of [pkg] (the bridge's link to the phone) before the
     * package goes. Errors are only logged, never fail or skip the removal; a lost watch is [Down].
     */
    private suspend fun dropCompanionAssociations(pkg: String) {
        val found = when (val r = guarded { watch.companionAssociations() }) {
            is Call.Ok -> r.value
            is Call.Unreachable -> throw Down(r.cause)
            is Call.Error -> {
                log("revert: companion associations of $pkg not listed: ${r.message}")
                return
            }
        }
        for (association in found.filter { it.packageName == pkg }) {
            when (val r = guarded { watch.disassociateCompanion(pkg, association.mac) }) {
                is Call.Ok -> Unit
                is Call.Unreachable -> throw Down(r.cause)
                is Call.Error -> log("revert: companion association of $pkg not removed: ${r.message}")
            }
        }
    }

    private suspend fun enable(item: RevertItem): StepOutcome {
        if (!guarded { watch.isDisabled(item.pkg) }.get()) return StepOutcome.Skipped(SkipReason.NothingToDo)
        guarded { watch.enable(item.pkg) }.get()
        return StepOutcome.Done
    }

    private suspend fun restore(item: RevertItem): StepOutcome {
        if (guarded { watch.secureSetting(item.pkg) }.get() != "0") return StepOutcome.Skipped(SkipReason.NothingToDo)
        guarded { watch.putSecureSetting(item.pkg, RevertPlan.ALWAYS_ON_DEFAULT) }.get()
        return StepOutcome.Done
    }

    private suspend fun removeExemption(item: RevertItem): StepOutcome {
        val current = guarded { watch.globalSetting(item.pkg) }.get()
        if (!GrantReadback.listContainsEntry(current, RevertPlan.HIDDEN_API_ENTRY)) return StepOutcome.Skipped(SkipReason.NothingToDo)
        val rest = GrantReadback.removeListEntry(current, RevertPlan.HIDDEN_API_ENTRY)
        if (rest == null) {
            guarded { watch.deleteGlobalSetting(item.pkg) }.get()
        } else {
            if (rest.any { it == '\'' || it.isISOControl() }) {
                return StepOutcome.Failed("The ${item.pkg} list cannot be rewritten safely")
            }
            guarded { watch.putGlobalSetting(item.pkg, rest) }.get()
        }
        return StepOutcome.Done
    }

    private fun messageOf(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private companion object {
        /** The platform's text for a removal the user declined. */
        const val ABORTED = "STATUS_FAILURE_ABORTED"
    }
}
