package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.PackagePrefix
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.SetupFlow
import io.openshm.patcher.ui.screens.customize.CustomizeEvent
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

    private val session = SessionState(watchProfile = WatchProfile.WearOs5, packagePrefix = "io.openshm.u3fa91c")

    @Test
    fun everyCataloguePatchIsRequiredSoPresetsAreHidden() {
        val s = customizeStateFor(session)
        assertEquals(3, s.appCount)
        assertEquals(0, s.optionalCount)
        assertFalse(s.showPresets)
        val expected = AppCatalogue.current.patchSets
            .filter { it.inputId in session.watchProfile.inputIds }
            .sumOf { it.patches.size }
        assertEquals(expected, s.enabledPatchCount)
        assertTrue(s.canBuild)
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
