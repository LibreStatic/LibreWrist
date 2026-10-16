package io.openshm.patcher.device

import io.openshm.patcher.adb.SelfAdbState
import io.openshm.patcher.ui.screens.install.NEEDS_PAIRING_SCREEN
import io.openshm.patcher.ui.screens.install.toPhase

/**
 * Whether the app should come back to the front once the phone's Wireless debugging connects: only
 * when the attempt sent the user to Developer options (turn it on, pair with a code). A stored
 * pairing that reconnects on its own never left the app, so it is not brought forward. Fires once
 * per visit to the pairing screen.
 */
internal class ReturnAfterPairing {
    private var away = false

    /** Feed every session state in order; true on the [SelfAdbState.Connected] that ends a visit. */
    fun onState(state: SelfAdbState): Boolean {
        if (state.toPhase() in NEEDS_PAIRING_SCREEN) away = true
        if (state !is SelfAdbState.Connected || !away) return false
        away = false
        return true
    }

    companion object {
        /**
         * The shell command that brings [packageName]'s [activity] to the front. It runs over the
         * phone's own ADB connection: the shell user may start activities from the background,
         * which the app itself may not while Settings is in front.
         */
        fun command(packageName: String, activity: String): String =
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $packageName/$activity"
    }
}
