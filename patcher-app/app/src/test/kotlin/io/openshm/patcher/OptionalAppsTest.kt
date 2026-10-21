package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.data.seedOffByDefault
import io.openshm.patcher.ui.nav.Route
import io.openshm.patcher.ui.nav.selectionSkippedRoutes
import io.openshm.patcher.ui.screens.acquire.AcquireCard
import io.openshm.patcher.ui.screens.acquire.AcquireProgress
import io.openshm.patcher.ui.screens.acquire.buildCards
import io.openshm.patcher.ui.screens.acquire.optionalToggles
import io.openshm.patcher.ui.screens.acquire.symmetricDifference
import io.openshm.patcher.ui.screens.acquire.toggledSkips
import io.openshm.patcher.ui.screens.customize.customizeStateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OptionalAppsTest {
    private val catalogue = TestCatalogue.load()

    @Before
    fun install() = AppCatalogue.install(catalogue)

    private fun cards(ids: List<String>, existing: List<AcquireCard> = emptyList()) =
        buildCards(ids, existing, catalogue::input) { AcquireProgress.Missing }

    private val all = catalogue.apps.filter { it.optional }.map { it.id }.toSet()

    @Test
    fun everyOptionalAppIsOnByDefaultInTheStableOrder() {
        val fresh = SessionState(watchProfile = WatchProfile.WearOs5)
        assertTrue(fresh.skippedApps.isEmpty())
        assertEquals(listOf("shm-phone", "shm-watch", "alarm-watch", "shealth-phone"), fresh.inputIds)
        assertTrue(optionalToggles(catalogue, WatchProfile.WearOs5.sdk, fresh.skippedApps).all { it.enabled })
    }

    @Test
    fun healthMonitorOffKeepsTheBridgesAndTheAlarmFork() {
        val s = SessionState(watchProfile = WatchProfile.WearOs5, skippedApps = setOf("health-monitor"))
        assertEquals(listOf("alarm-watch", "shealth-phone"), s.inputIds)
        assertFalse("shm-phone" in s.outputIds)
        assertFalse("shm-watch" in s.outputIds)
        assertTrue("bridge-phone" in s.outputIds && "bridge-watch" in s.outputIds && "alarm-fork" in s.outputIds)
    }

    @Test
    fun everythingOffLeavesNothingToBuild() {
        val s = SessionState(watchProfile = WatchProfile.WearOs3Or4, skippedApps = all)
        assertTrue(s.inputIds.isEmpty())
        assertTrue(s.outputIds.isEmpty())
    }

    @Test
    fun bridgeOffDropsItsOutputsAndTheAppsThatRequireIt() {
        val s = SessionState(watchProfile = WatchProfile.WearOs5, skippedApps = setOf("bridge"))
        assertEquals(listOf("shm-phone", "shm-watch", "shealth-phone"), s.outputIds)
        assertEquals(setOf("bridge", "alarm"), catalogue.effectiveSkipped(setOf("bridge")))
        assertEquals(setOf("alarm"), catalogue.effectiveSkipped(setOf("alarm")))
        assertEquals(listOf("shm-phone", "shm-watch", "shealth-phone"), s.inputIds.map { id -> catalogue.outputs.first { it.inputId == id }.id })
    }

    @Test
    fun alarmsSwitchIsOffWhenTheAlarmAppIsSkippedOrTheBridgeIs() {
        val bridgeWatch = catalogue.output("bridge-watch")
        val sdk = WatchProfile.WearOs5.sdk
        assertEquals(mapOf("alarms" to true), catalogue.startSwitches(bridgeWatch, sdk, emptySet()))
        assertEquals(mapOf("alarms" to false), catalogue.startSwitches(bridgeWatch, sdk, setOf("alarm")))
        assertEquals(mapOf("alarms" to false), catalogue.startSwitches(bridgeWatch, sdk, setOf("bridge")))
        // a watch that cannot run the alarm fork keeps the bridge's fallback
        assertEquals(mapOf("alarms" to true), catalogue.startSwitches(bridgeWatch, WatchProfile.WearOs3Or4.sdk, setOf("bridge")))
    }

    @Test
    fun togglingFollowsTheRequirements() {
        val sdk = WatchProfile.WearOs5.sdk
        // bridge off takes the alarm app with it, and both show as off
        val off = toggledSkips(catalogue, emptySet(), "bridge")
        assertEquals(setOf("bridge", "alarm"), off)
        assertEquals(listOf(true, false, false, true), optionalToggles(catalogue, sdk, off).map { it.enabled })
        // bridge back on leaves the alarm app off
        assertEquals(setOf("alarm"), toggledSkips(catalogue, off, "bridge"))
        // alarm on pulls the bridge on
        assertEquals(emptySet<String>(), toggledSkips(catalogue, off, "alarm"))
        // alarm off alone keeps the bridge
        assertEquals(setOf("alarm"), toggledSkips(catalogue, emptySet(), "alarm"))
        // an app without requirements is a plain flip
        assertEquals(setOf("samsung-health"), toggledSkips(catalogue, emptySet(), "samsung-health"))
        // a stored skip set that only lists the bridge already shows the alarm app off
        assertEquals(listOf(true, false, false, true), optionalToggles(catalogue, sdk, setOf("bridge")).map { it.enabled })
    }

    @Test
    fun samsungHealthOffDropsOnlyItsInput() {
        val s = SessionState(watchProfile = WatchProfile.WearOs3Or4, skippedApps = setOf("samsung-health"))
        assertEquals(listOf("shm-phone", "shm-watch"), s.inputIds)
        assertFalse("shealth-phone" in s.outputIds)
    }

    @Test
    fun theToggleFlipsTheIdInTheSkippedSet() {
        assertEquals(setOf("samsung-health"), emptySet<String>().symmetricDifference("samsung-health"))
        assertEquals(emptySet<String>(), setOf("samsung-health").symmetricDifference("samsung-health"))
        assertEquals(listOf(true, true, false), optionalToggles(catalogue, WatchProfile.WearOs3Or4.sdk, setOf("samsung-health")).map { it.enabled })
    }

    @Test
    fun togglingKeepsTheProgressOfTheCardsThatStay() {
        val base = SessionState(watchProfile = WatchProfile.WearOs3Or4, skippedApps = setOf("samsung-health"))
        val before = cards(base.inputIds).map { if (it.input.id == "shm-phone") it.copy(progress = AcquireProgress.Verified(5)) else it }
        val after = cards(base.copy(skippedApps = emptySet()).inputIds, before)
        assertEquals(listOf("shm-phone", "shm-watch", "shealth-phone"), after.map { it.input.id })
        assertEquals(AcquireProgress.Verified(5), after.first { it.input.id == "shm-phone" }.progress)
        assertEquals(AcquireProgress.Missing, after.last().progress)
        val off = cards(base.copy(skippedApps = setOf("health-monitor")).inputIds, before)
        assertEquals(listOf("shealth-phone"), off.map { it.input.id })
    }

    @Test
    fun customizeStillBuildsTheOwnAppsWhenNoOfficialAppIsPatched() {
        val s = customizeStateFor(SessionState(watchProfile = WatchProfile.WearOs3Or4, skippedApps = all))
        assertTrue(s.groups.isEmpty())
        assertTrue(s.canBuild)
    }

    private val devMode = catalogue.platform.healthPlatform.requiredBy
    private val stock = catalogue.stockAppsToDisable

    @Test
    fun developerModeAndStockStepsArePassedOverWithoutTheOutputsThatNeedThem() {
        assertEquals(setOf(Route.DevMode, Route.DisableStock), selectionSkippedRoutes(listOf("bridge-phone", "alarm-fork"), devMode, stock))
        assertTrue(selectionSkippedRoutes(listOf("shm-watch", "bridge-watch"), devMode, stock).isEmpty())
    }

    @Test
    fun theStepGatesComeFromTheCatalogueNotFromOutputIds() {
        assertEquals(setOf(Route.DevMode), selectionSkippedRoutes(listOf("shm-watch"), emptyList(), stock))
        assertEquals(setOf(Route.DisableStock), selectionSkippedRoutes(listOf("shm-watch"), devMode, emptyList()))
    }

    @Test
    fun selectionDependsOnTheProfileSdkAgainstTheInputMinSdk() {
        val wear5 = catalogue.selectedOutputs(WatchProfile.WearOs5.sdk, emptySet()).map { it.id }
        val wear3 = catalogue.selectedOutputs(WatchProfile.WearOs3Or4.sdk, emptySet()).map { it.id }
        assertTrue("alarm-fork" in wear5)
        assertFalse("alarm-fork" in wear3)
        assertTrue(wear3.all { it in wear5 })
    }

    private val offByDefault = catalogue.engine.patterns.values.first { it.offByDefault }.id

    @Test
    fun firstRunSeedsOffByDefaultPatchesAsDisabled() {
        val (disabled, known) = seedOffByDefault(null, null, catalogue.engine)
        assertTrue(offByDefault in disabled)
        assertTrue(offByDefault in known)
    }

    @Test
    fun anOptionalPatchTheUserReEnabledStaysEnabled() {
        val (disabled, _) = seedOffByDefault(emptySet(), setOf(offByDefault), catalogue.engine)
        assertFalse(offByDefault in disabled)
    }

    @Test
    fun aNewlyAppearingOffByDefaultPatchIsDisabledOnce() {
        val (disabled, known) = seedOffByDefault(emptySet(), emptySet(), catalogue.engine)
        assertTrue(offByDefault in disabled)
        val (again, _) = seedOffByDefault(disabled - offByDefault, known, catalogue.engine)
        assertFalse(offByDefault in again)
    }
}
