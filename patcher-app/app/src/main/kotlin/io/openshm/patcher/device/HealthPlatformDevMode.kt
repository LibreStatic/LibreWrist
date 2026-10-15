package io.openshm.patcher.device

import io.openshm.patcher.adb.AdbClient
import io.openshm.patcher.engine.HealthPlatform
import kotlinx.coroutines.delay

/**
 * Health Platform developer mode on the watch, driven over watch ADB. The flag itself is internal to the
 * service, so it is read from the service's settings screen, which shows a subtitle view under its title
 * while the mode is on, and turned on by tapping that title several times (docs/health-tracking-protocol.md
 * §8). Both are found by view id, so the watch's language does not matter. The package, screen, view ids
 * and tap count are the catalogue's `platform.healthPlatform`.
 */
class HealthPlatformDevMode(private val platform: HealthPlatform, private val client: suspend () -> AdbClient) {

    /** What the settings screen shows, read from its view hierarchy. */
    data class SettingsScreen(val devModeShown: Boolean, val titleCenter: Pair<Int, Int>?)

    /** Wakes the watch and brings up the Health Platform settings screen. */
    suspend fun openSettings() {
        val adb = client()
        adb.shell("input keyevent KEYCODE_WAKEUP")
        adb.shell("am start -a ${platform.settingsAction} -n ${platform.settingsComponent}").requireSuccess("am start")
    }

    /** Whether a Health Platform screen is the one in front on the watch. */
    suspend fun settingsInFront(): Boolean {
        val out = client().shell("dumpsys activity activities 2>/dev/null | grep topResumedActivity").stdout
        return isHealthPlatformInFront(out, platform)
    }

    /**
     * Reads the settings screen's view hierarchy from `dumpsys activity top`. Not a UI Automator
     * dump: ending a UI Automator session "restores" the user rotation, and on a watch worn with the
     * button on the right that flips the screen upside down each time.
     */
    suspend fun readScreen(): SettingsScreen =
        parseSettingsScreen(client().shell("dumpsys activity top", DUMP_TIMEOUT_MS).stdout, platform)

    /**
     * Turns developer mode on: opens the settings, and only if the mode is not shown already, taps
     * the title `taps` times, then reads the screen again. The taps toggle the flag, so they are
     * never sent while the mode is on. Returns whether the mode is shown at the end.
     */
    suspend fun enable(): Boolean {
        openSettings()
        delay(SETTLE_MS)
        val before = readScreen()
        if (before.devModeShown) return true
        val (x, y) = before.titleCenter ?: throw TitleNotFound()
        client().shell(
            "for i in \$(seq ${platform.taps}); do input tap $x $y; done",
            TAP_TIMEOUT_MS,
        ).requireSuccess("input tap")
        delay(SETTLE_MS)
        return readScreen().devModeShown
    }

    /** The settings screen did not show its title (locked watch, screen off, or a changed layout). */
    class TitleNotFound : Exception("Health Platform settings title not found on the watch screen")

    companion object {
        private const val DUMP_TIMEOUT_MS = 15_000L
        private const val TAP_TIMEOUT_MS = 30_000L
        private const val SETTLE_MS = 1_500L

        /** A view line: indent, class{hash FLAGS flags2 l,t-r,b [#hex id]} (the flags' first letter is V, I or G). */
        private val VIEW = Regex("""^(\s*)\S+\{[0-9a-f]+ ([VIG])\S* \S+ (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?: #[0-9a-f]+ ([^\s}]+))?""")

        internal fun isHealthPlatformInFront(dumpsys: String, platform: HealthPlatform): Boolean =
            dumpsys.lineSequence().any { "topResumedActivity" in it && " ${platform.packageName}/" in it }

        /**
         * Finds the title and the developer-mode subtitle in the Health Platform activity's part of
         * `dumpsys activity top`. View bounds there are relative to the parent, so the title's
         * centre is made absolute by adding the offsets of the enclosing views (by indentation).
         */
        internal fun parseSettingsScreen(dumpsysTop: String, platform: HealthPlatform): SettingsScreen {
            var shown = false
            var title: Pair<Int, Int>? = null
            var inActivity = false
            val parents = ArrayList<Triple<Int, Int, Int>>() // indent, absolute left, absolute top
            for (line in dumpsysTop.lineSequence()) {
                if (line.startsWith("  ACTIVITY ")) {
                    inActivity = line.substringAfter("  ACTIVITY ").startsWith("${platform.packageName}/")
                    parents.clear()
                    continue
                }
                if (!inActivity) continue
                val m = VIEW.find(line) ?: continue
                val indent = m.groupValues[1].length
                while (parents.isNotEmpty() && parents.last().first >= indent) parents.removeAt(parents.size - 1)
                val (baseX, baseY) = parents.lastOrNull()?.let { it.second to it.third } ?: (0 to 0)
                val left = baseX + m.groupValues[3].toInt()
                val top = baseY + m.groupValues[4].toInt()
                val right = baseX + m.groupValues[5].toInt()
                val bottom = baseY + m.groupValues[6].toInt()
                val visible = m.groupValues[2] == "V"
                when (m.groupValues[7]) {
                    platform.devModeViewId -> if (visible) shown = true
                    platform.titleViewId -> if (visible) title = (left + right) / 2 to (top + bottom) / 2
                    else -> {}
                }
                parents += Triple(indent, left, top)
            }
            return SettingsScreen(shown, title)
        }
    }
}
