package io.openshm.patcher.ui.nav

import androidx.annotation.StringRes
import io.openshm.patcher.R
import io.openshm.patcher.device.DetectedWatch

/** A setup step passed over because what it asks for is already true; [watch] fills the reason when it is about the watch. */
data class SkippedStep(val route: Route, @StringRes val reason: Int, val watch: DetectedWatch? = null)

/**
 * The steps after the welcome that need nothing from the user: the profile and the watch pairing once
 * the remembered watch answered over ADB, the Galaxy Wearable step once all its checks pass.
 */
internal fun skippableSteps(watch: DetectedWatch?, wearableReady: Boolean): List<SkippedStep> = buildList {
    if (watch != null) add(SkippedStep(Route.Profile, R.string.skip_reason_profile, watch))
    if (wearableReady) add(SkippedStep(Route.WearablePairing, R.string.skip_reason_wearable))
    if (watch != null) add(SkippedStep(Route.WatchPair, R.string.skip_reason_watchpair))
}

/** The steps skipped in this run of the setup; the nav host's `next` passes over them. */
object StepSkips {
    @Volatile
    var current: List<SkippedStep> = emptyList()

    val routes: Set<Route> get() = current.mapTo(mutableSetOf()) { it.route }
}
