package io.openshm.patcher

import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.SAMSUNG_HEALTH_INPUT
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.WatchProfile
import io.openshm.patcher.data.seedOffByDefault
import io.openshm.patcher.ui.screens.acquire.AcquireProgress
import io.openshm.patcher.ui.screens.acquire.buildCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SamsungHealthOptInTest {
    private val catalogue = TestCatalogue.load()

    @Before
    fun install() = AppCatalogue.install(catalogue)

    private fun cards(ids: List<String>, existing: List<io.openshm.patcher.ui.screens.acquire.AcquireCard> = emptyList()) =
        buildCards(ids, existing, catalogue::input) { AcquireProgress.Missing }

    @Test
    fun samsungHealthIsOnByDefault() {
        val fresh = SessionState(watchProfile = WatchProfile.WearOs3Or4)
        assertTrue(fresh.samsungHealth)
        assertTrue(SAMSUNG_HEALTH_INPUT in fresh.inputIds)
        assertTrue("shealth-phone" in fresh.outputIds)
    }

    @Test
    fun sessionIncludesSamsungHealthOnlyWhenChosen() {
        val on = SessionState(watchProfile = WatchProfile.WearOs3Or4)
        val off = on.copy(samsungHealth = false)
        assertEquals(WatchProfile.WearOs3Or4.inputIds, off.inputIds)
        assertEquals(WatchProfile.WearOs3Or4.inputIds + SAMSUNG_HEALTH_INPUT, on.inputIds)
        assertFalse(SAMSUNG_HEALTH_INPUT in off.inputIds)
        assertFalse("shealth-phone" in off.outputIds)
        assertTrue("shealth-phone" in on.outputIds)
    }

    @Test
    fun togglingOnAppendsTheCardAndKeepsProgress() {
        val base = SessionState(watchProfile = WatchProfile.WearOs3Or4, samsungHealth = false)
        val before = cards(base.inputIds).map { if (it.input.id == "shm-phone") it.copy(progress = AcquireProgress.Verified(5)) else it }
        val after = cards(base.copy(samsungHealth = true).inputIds, before)
        assertEquals(WatchProfile.WearOs3Or4.inputIds + SAMSUNG_HEALTH_INPUT, after.map { it.input.id })
        assertEquals(AcquireProgress.Verified(5), after.first { it.input.id == "shm-phone" }.progress)
        assertEquals(AcquireProgress.Missing, after.last().progress)
    }

    @Test
    fun togglingOffRemovesTheCardAndKeepsProgress() {
        val on = SessionState(watchProfile = WatchProfile.WearOs3Or4, samsungHealth = true)
        val before = cards(on.inputIds).map { if (it.input.id == "shm-watch") it.copy(progress = AcquireProgress.Verified(7)) else it }
        val after = cards(on.copy(samsungHealth = false).inputIds, before)
        assertEquals(WatchProfile.WearOs3Or4.inputIds, after.map { it.input.id })
        assertEquals(AcquireProgress.Verified(7), after.first { it.input.id == "shm-watch" }.progress)
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
