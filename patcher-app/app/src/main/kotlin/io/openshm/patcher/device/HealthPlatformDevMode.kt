package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbClient
import kotlinx.coroutines.delay

/**
 * Health Platform developer mode on the watch, driven over watch ADB with UI Automator dumps. The
 * flag itself is internal to the service, so it is read from the service's settings screen, which
 * shows a `dev_mode_subtitle` under its title while the mode is on, and turned on by tapping that
 * title 10 times (docs/health-tracking-protocol.md §8). Both are found by resource id, so the
 * watch's language does not matter.
 */
class HealthPlatformDevMode(private val client: suspend () -> AdbClient) {

    /** What the settings screen shows, read from a UI dump. */
    data class SettingsScreen(val devModeShown: Boolean, val titleCenter: Pair<Int, Int>?)

    /** Wakes the watch and brings up the Health Platform settings screen. */
    suspend fun openSettings() {
        val adb = client()
        adb.shell("input keyevent KEYCODE_WAKEUP")
        adb.shell("am start -a $SETTINGS_ACTION -n $SETTINGS_COMPONENT").requireSuccess("am start")
    }

    /** Whether a Health Platform screen is the one in front on the watch. */
    suspend fun settingsInFront(): Boolean {
        val out = client().shell("dumpsys activity activities 2>/dev/null | grep topResumedActivity").stdout
        return isHealthPlatformInFront(out)
    }

    /**
     * Dumps the watch's screen. Only meaningful while the settings screen is in front and awake; a
     * failed dump reads as an empty screen.
     */
    suspend fun readScreen(): SettingsScreen {
        val out = client().shell(
            "uiautomator dump $DUMP_PATH >/dev/null 2>&1; cat $DUMP_PATH 2>/dev/null; rm -f $DUMP_PATH",
            DUMP_TIMEOUT_MS,
        ).stdout
        return parseSettingsScreen(out)
    }

    /**
     * Turns developer mode on: opens the settings, and only if the mode is not shown already, taps
     * the title [TAPS] times, then reads the screen again. The taps toggle the flag, so they are
     * never sent while the mode is on. Returns whether the mode is shown at the end.
     */
    suspend fun enable(): Boolean {
        openSettings()
        delay(SETTLE_MS)
        val before = readScreen()
        if (before.devModeShown) return true
        val (x, y) = before.titleCenter ?: throw TitleNotFound()
        client().shell(
            "for i in \$(seq $TAPS); do input tap $x $y; done",
            TAP_TIMEOUT_MS,
        ).requireSuccess("input tap")
        delay(SETTLE_MS)
        return readScreen().devModeShown
    }

    /** The settings screen did not show its title (locked watch, screen off, or a changed layout). */
    class TitleNotFound : Exception("Health Platform settings title not found on the watch screen")

    companion object {
        const val PACKAGE = "com.samsung.android.service.health"
        const val SETTINGS_ACTION = "$PACKAGE.intent.action.START_SETTINGS_ACTIVITY"
        const val SETTINGS_COMPONENT = "$PACKAGE/.wear.settings.home.SettingsMainActivity"
        const val TITLE_ID = "$PACKAGE:id/title"
        const val SUBTITLE_ID = "$PACKAGE:id/dev_mode_subtitle"
        const val TAPS = 10
        private const val DUMP_PATH = "/data/local/tmp/openshm-ui.xml"
        private const val DUMP_TIMEOUT_MS = 15_000L
        private const val TAP_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 1_500L

        private val NODE = Regex("<node [^>]*>")
        private val ATTR = Regex("""([\w:-]+)="([^"]*)"""")
        private val BOUNDS = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")

        internal fun isHealthPlatformInFront(dumpsys: String): Boolean =
            dumpsys.lineSequence().any { "topResumedActivity" in it && " $PACKAGE/" in it }

        internal fun parseSettingsScreen(uiDump: String): SettingsScreen {
            var shown = false
            var title: Pair<Int, Int>? = null
            for (node in NODE.findAll(uiDump)) {
                val attrs = ATTR.findAll(node.value).associate { it.groupValues[1] to it.groupValues[2] }
                when (attrs["resource-id"]) {
                    SUBTITLE_ID -> if (!attrs["text"].isNullOrBlank()) shown = true
                    TITLE_ID -> BOUNDS.matchEntire(attrs["bounds"].orEmpty())?.let { m ->
                        val (l, t, r, b) = m.destructured
                        title = (l.toInt() + r.toInt()) / 2 to (t.toInt() + b.toInt()) / 2
                    }
                }
            }
            return SettingsScreen(shown, title)
        }
    }
}
