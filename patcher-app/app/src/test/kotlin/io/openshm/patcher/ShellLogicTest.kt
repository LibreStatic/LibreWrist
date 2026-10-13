package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.screens.customize.CustomizeEvent
import io.openshm.patcher.ui.screens.customize.CustomizeUiState
import io.openshm.patcher.ui.screens.customize.Preset
import io.openshm.patcher.ui.screens.customize.customizeStateFor
import io.openshm.patcher.ui.screens.customize.reduce
import io.openshm.patcher.ui.theme.isReducedMotion
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PackagePrefixTest {
    @Test
    fun generatedPrefixIsValidAndHasSixHexDigits() {
        repeat(50) {
            val p = PackagePrefix.generate(Random(it.toLong()))
            assertTrue(p, Regex("io\\.openshm\\.u[0-9a-f]{6}").matches(p))
            assertNull(PackagePrefix.validate(p))
        }
    }

    @Test
    fun rejectsBadPrefixes() {
        assertEquals(PackagePrefix.Problem.Empty, PackagePrefix.validate(""))
        assertEquals(PackagePrefix.Problem.TooShort, PackagePrefix.validate("openshm"))
        assertEquals(PackagePrefix.Problem.InvalidSegment, PackagePrefix.validate("io.1bad"))
        assertEquals(PackagePrefix.Problem.InvalidSegment, PackagePrefix.validate("io..x"))
        assertEquals(PackagePrefix.Problem.Reserved, PackagePrefix.validate("com.samsung.mine"))
        assertEquals(PackagePrefix.Problem.Reserved, PackagePrefix.validate("android.foo"))
        assertEquals(PackagePrefix.Problem.TooLong, PackagePrefix.validate("a." + "b".repeat(250)))
    }
}

class MotionTest {
    @Test
    fun onlyAZeroAnimatorScaleIsReducedMotion() {
        assertTrue(isReducedMotion(0f))
        assertFalse(isReducedMotion(1f))
        assertFalse(isReducedMotion(0.5f))
    }
}

class SetupFlowTest {
    @Test
    fun orderFollowsTheGuidedSetup() {
        assertEquals(Route.Welcome, SetupFlow.order.first())
        assertEquals(Route.Home, SetupFlow.order.last())
        assertEquals(Route.Profile, SetupFlow.next(Route.Welcome))
        assertEquals(Route.Customize, SetupFlow.next(Route.Acquire))
        assertEquals(Route.Build, SetupFlow.next(Route.Customize))
        assertNull(SetupFlow.next(Route.Home))
        assertNull(SetupFlow.next(Route.GuidedBrowser))
        assertEquals(SetupFlow.order.size, SetupFlow.order.toSet().size)
    }
}

class CustomizeStateTest {
    @Before fun catalogue() = TestCatalogue.install()

    private val session = SessionState(watchProfile = WatchProfile.WearOs5, packagePrefix = "io.openshm.u3fa91c", samsungHealth = false)

    private val optionalIds = setOf(
        "shm-min-age-22-to-16", "shm-setup-network-gate", "shm-sleep-apnea-support", "watch-apnea-country",
        "watch-apnea-reeval-not-supported", "alarm-force-sound-vibrate", "alarm-hide-launcher", "alarm-relabel", "shm-launcher-badge",
    )
    private val apneaIds = setOf("shm-sleep-apnea-support", "watch-apnea-country", "watch-apnea-reeval-not-supported")

    private fun CustomizeUiState.patch(id: String) = groups.flatMap { it.patches }.first { it.info.id == id }
    private fun CustomizeUiState.toggle(id: String): CustomizeUiState {
        val group = groups.first { g -> g.patches.any { it.info.id == id } }
        return reduce(CustomizeEvent.TogglePatch(group.inputId, id))
    }

    @Test
    fun optionalPatchesStartEnabledAndPresetsAreShown() {
        val s = customizeStateFor(session)
        assertEquals(3, s.appCount)
        assertEquals(9, s.optionalCount)
        assertEquals(optionalIds, s.groups.flatMap { it.patches }.filter { !it.required }.map { it.info.id }.toSet())
        assertTrue(s.showPresets)
        assertEquals(Preset.Recommended, s.preset)
        assertTrue(s.disabledIds.isEmpty())
        val expected = AppCatalogue.current.patchSets
            .filter { it.inputId in session.watchProfile.inputIds }
            .sumOf { it.patches.size }
        assertEquals(expected, s.enabledPatchCount)
        assertTrue(s.canBuild)
    }

    @Test
    fun togglingAnOptionalPatchUpdatesTheSummaryCount() {
        val s = customizeStateFor(session)
        val off = s.toggle("alarm-relabel")
        assertFalse(off.patch("alarm-relabel").enabled)
        assertEquals(s.enabledPatchCount - 1, off.enabledPatchCount)
        assertEquals(setOf("alarm-relabel"), off.disabledIds)
        assertNull(off.preset)
        assertEquals(s, off.toggle("alarm-relabel"))
    }

    @Test
    fun sleepApneaPatchesToggleTogetherAcrossApps() {
        val s = customizeStateFor(session)
        for (start in apneaIds) {
            val off = s.toggle(start)
            assertEquals(start, apneaIds, off.disabledIds)
            assertEquals(s.enabledPatchCount - 3, off.enabledPatchCount)
            assertEquals(s, off.toggle(apneaIds.first { it != start }))
        }
    }

    @Test
    fun presetsSwitchEveryOptionalPatch() {
        val s = customizeStateFor(session)
        val minimal = s.reduce(CustomizeEvent.SelectPreset(Preset.Minimal))
        assertEquals(Preset.Minimal, minimal.preset)
        assertEquals(optionalIds, minimal.disabledIds)
        assertEquals(s.enabledPatchCount - 9, minimal.enabledPatchCount)
        assertTrue(minimal.canBuild)
        assertEquals(s, minimal.reduce(CustomizeEvent.SelectPreset(Preset.Recommended)))
    }

    @Test
    fun savedChoiceIsRestoredAndStaleIdsAreIgnored() {
        val restored = customizeStateFor(session.copy(disabledPatches = setOf("alarm-hide-launcher", "no-such-pattern", "generic-vendor-check")))
        assertEquals(setOf("alarm-hide-launcher"), restored.disabledIds)
        // a partly saved option group is completed instead of failing the build later
        assertEquals(apneaIds, customizeStateFor(session.copy(disabledPatches = setOf("watch-apnea-country"))).disabledIds)
    }

    @Test
    fun skippedPatchesAreNotCounted() {
        val c = AppCatalogue.current
        val all = c.patternCount(session.watchProfile.inputIds)
        assertEquals(all - 3, c.patternCount(session.watchProfile.inputIds, apneaIds))
        assertEquals(all - 1, c.patternCount(session.watchProfile.inputIds, setOf("alarm-relabel", "generic-vendor-check", "nope")))
    }

    @Test
    fun olderWatchProfileDropsTheAlarmApp() {
        assertEquals(2, customizeStateFor(session.copy(watchProfile = WatchProfile.WearOs3Or4)).appCount)
    }

    @Test
    fun requiredPatchesCannotBeToggled() {
        val s = customizeStateFor(session)
        val g = s.groups.first()
        val after = s.reduce(CustomizeEvent.TogglePatch(g.inputId, g.patches.first().info.id))
        assertEquals(s.enabledPatchCount, after.enabledPatchCount)
    }

    @Test
    fun invalidPrefixBlocksBuild() {
        val s = customizeStateFor(session).reduce(CustomizeEvent.PrefixChanged("nope"))
        assertNotNull(s.prefixProblem)
        assertFalse(s.canBuild)
    }

    @Test
    fun lockedPrefixIgnoresEdits() {
        val s = customizeStateFor(session.copy(prefixLocked = true))
        assertEquals(s, s.reduce(CustomizeEvent.PrefixChanged("io.other.x")))
        assertEquals(s, s.reduce(CustomizeEvent.RegeneratePrefix) { "io.other.y" })
    }

    @Test
    fun regenerateUsesTheGenerator() {
        val s = customizeStateFor(session).reduce(CustomizeEvent.RegeneratePrefix) { "io.openshm.uabcdef" }
        assertEquals("io.openshm.uabcdef", s.packagePrefix)
        assertNull(s.prefixProblem)
    }
}
