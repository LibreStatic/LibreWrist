package io.openshm.patcher.ui.nav

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.device.DetectedWatch

/** A setup step passed over because what it asks for is already true; [watch] fills the reason when it is about the watch. */
data class SkippedStep(val route: Route, @StringRes val reason: Int, val watch: DetectedWatch? = null)

/**
 * The steps after the welcome that need nothing from the user: the profile once the watch told its
 * version (over ADB or in its Wi-Fi announcement), the watch pairing once the remembered watch answered
 * over ADB, the Galaxy Wearable step once all its checks pass.
 */
internal fun skippableSteps(watch: DetectedWatch?, wearableReady: Boolean): List<SkippedStep> = buildList {
    if (watch != null) {
        val reason = if (watch.source == DetectedWatch.Source.Network) R.string.skip_reason_profile_network else R.string.skip_reason_profile
        add(SkippedStep(Route.Profile, reason, watch))
    }
    if (wearableReady) add(SkippedStep(Route.WearablePairing, R.string.skip_reason_wearable))
    // An announcement says nothing about the pairing: only an ADB answer proves the link.
    if (watch?.source == DetectedWatch.Source.Adb) add(SkippedStep(Route.WatchPair, R.string.skip_reason_watchpair))
}

/**
 * The skipped steps that come right after [route], in order: the run [SetupFlow.next] passes over when
 * leaving [route]. Empty when the next step needs the user.
 */
internal fun skippedRunAfter(route: Route, skipped: List<SkippedStep>): List<SkippedStep> = buildList {
    var target = SetupFlow.next(route)
    while (target != null) {
        val step = skipped.firstOrNull { it.route == target } ?: break
        add(step)
        target = SetupFlow.next(target)
    }
}

/**
 * The steps a per-app rebuild of [scope] passes over without a word (nothing is announced): the phone
 * install without a phone output, the watch install without a watch output, and the developer-mode and
 * stock-app steps unless the Health Monitor on the watch ("shm-watch") is rebuilt. Empty outside a
 * rebuild (null [scope]).
 */
internal fun rebuildSkippedRoutes(outputs: List<OutputApp>, scope: Set<String>?): Set<Route> {
    if (scope == null) return emptySet()
    val inScope = outputs.filter { it.id in scope }
    return buildSet {
        if (inScope.none { it.device == Device.Phone }) add(Route.InstallPhone)
        if (inScope.none { it.device == Device.Watch }) add(Route.WatchInstall)
        if ("shm-watch" !in scope) {
            add(Route.DevMode)
            add(Route.DisableStock)
        }
    }
}

/** The steps skipped in this run of the setup; the nav host's `next` passes over them. */
object StepSkips {
    @Volatile
    var current: List<SkippedStep> = emptyList()

    /** The user chose to walk the steps by hand: nothing is passed over or advanced automatically. */
    @Volatile
    var manual: Boolean = false

    val routes: Set<Route> get() = current.mapTo(mutableSetOf()) { it.route }
}
