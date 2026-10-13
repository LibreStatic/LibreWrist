package io.openshm.patcher.ui.nav

/** Every destination of the single activity. [path] is the Navigation Compose route. */
enum class Route(val path: String) {
    Welcome("welcome"),
    Profile("profile"),
    WearablePairing("wearable-pairing"),
    /** Sub-screen of the WearablePairing step ("Trouble pairing?"); not part of [SetupFlow]. */
    WearableTroubleshoot("wearable-troubleshoot"),
    WatchPair("watch-pair"),
    Acquire("acquire"),
    GuidedBrowser("guided-browser/{inputId}"),
    Customize("customize"),
    Build("build"),
    BuildDone("build-done"),
    InstallPhone("install-phone"),
    WatchInstall("watch-install"),
    DevMode("dev-mode"),
    DisableStock("disable-stock"),
    SelfTest("self-test"),
    AllSet("all-set"),
    Home("home"),
    /** Reached from Home, Welcome and the build-done nudge; not part of [SetupFlow]. */
    SigningKey("signing-key"),
    /** Reached from Home and Welcome; not part of [SetupFlow]. */
    Settings("settings"),
    /** Reached from Welcome, AllSet and the device logs notification; not part of [SetupFlow]. */
    DeviceLogs("device-logs"),
    /** "Undo all changes": reached from Welcome, AllSet and Home; not part of [SetupFlow]. */
    Revert("revert"),
    ;

    companion object {
        const val INPUT_ID_ARG = "inputId"

        fun guidedBrowser(inputId: String) = "guided-browser/$inputId"
    }
}

/**
 * The guided setup in the order it is walked. Each screen continues to the next one; screens that
 * only exist on some paths (the guided browser, Home) are not part of the sequence.
 */
object SetupFlow {
    val order: List<Route> = listOf(
        Route.Welcome,
        Route.Profile,
        Route.WearablePairing,
        Route.WatchPair,
        Route.Acquire,
        Route.Customize,
        Route.Build,
        Route.BuildDone,
        Route.InstallPhone,
        Route.WatchInstall,
        Route.DevMode,
        Route.DisableStock,
        Route.SelfTest,
        Route.AllSet,
        Route.Home,
    )

    fun next(route: Route): Route? {
        val index = order.indexOf(route)
        return if (index < 0) null else order.getOrNull(index + 1)
    }

    /** The step after [route], passing over the [skipped] ones. */
    fun next(route: Route, skipped: Set<Route>): Route? {
        var target = next(route)
        while (target != null && target in skipped) target = next(target)
        return target
    }
}
