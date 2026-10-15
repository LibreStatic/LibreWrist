package io.openshm.patcher

import io.openshm.patcher.data.SessionState
import io.openshm.patcher.ui.screens.customize.CustomizeEvent
import io.openshm.patcher.ui.screens.customize.CustomizeUiState
import io.openshm.patcher.ui.screens.customize.Preset
import io.openshm.patcher.ui.screens.customize.customizeStateFor
import io.openshm.patcher.ui.screens.customize.disabledIdsOver
import io.openshm.patcher.ui.screens.customize.editableIds
import io.openshm.patcher.ui.screens.customize.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CustomizeFocusTest {
    @Before fun catalogue() = TestCatalogue.install()

    private val session = SessionState()
    private val all by lazy { customizeStateFor(session) }
    private val focused by lazy { customizeStateFor(session, setOf("shm-watch")) }

    private fun CustomizeUiState.optionalOf(inputId: String) =
        groups.first { it.inputId == inputId }.patches.filter { !it.required }.map { it.info.id }

    @Test
    fun onlyTheFocusedGroupsAreVisibleButAllStayInState() {
        assertEquals(listOf("shm-watch"), focused.visibleGroups.map { it.inputId })
        assertEquals(all.groups.map { it.inputId }, focused.groups.map { it.inputId })
        assertEquals(all.visibleGroups, all.groups)
    }

    @Test
    fun anEmptyFocusShowsEverything() {
        assertEquals(null, customizeStateFor(session, emptySet()).focusInputIds)
    }

    @Test
    fun mergingTheDisabledSetKeepsTheUnfocusedGroupsEntries() {
        val other = all.optionalOf("shm-phone").first()
        val watch = focused.optionalOf("shm-watch").first()
        val previous = setOf(other)
        val toggled = focused.reduce(CustomizeEvent.TogglePatch("shm-watch", watch))
        val merged = toggled.disabledIdsOver(previous)
        assertTrue(other in merged)
        assertTrue(watch in merged)
    }

    @Test
    fun anEntryOfAnUnfocusedGroupSurvivesEvenWhenTheScreenDoesNotHoldIt() {
        val merged = focused.disabledIdsOver(setOf("not-a-known-patch"))
        assertTrue("not-a-known-patch" in merged)
    }

    @Test
    fun aPresetOnlyTouchesTheFocusedGroupsAndTheirSharedOptionGroups() {
        val editable = focused.editableIds()
        val minimal = focused.reduce(CustomizeEvent.SelectPreset(Preset.Minimal))
        val changed = minimal.groups.flatMap { it.patches }.filter { !it.required && !it.enabled }.map { it.info.id }.toSet()
        assertTrue(editable.containsAll(changed))
        assertTrue(focused.optionalOf("shm-watch").all { it in changed })
        // an unfocused patch outside the shared option groups keeps its state
        val untouched = all.groups.flatMap { it.patches }.filter { !it.required && it.info.id !in editable }
        untouched.forEach { p ->
            assertEquals(p.enabled, minimal.groups.flatMap { it.patches }.first { it.info.id == p.info.id }.enabled)
        }
    }

    @Test
    fun withoutFocusEveryOptionalPatchIsEditable() {
        val optional = all.groups.flatMap { it.patches }.filter { !it.required }.map { it.info.id }.toSet()
        assertEquals(optional, all.editableIds())
        assertEquals(all.reduce(CustomizeEvent.SelectPreset(Preset.Minimal)).disabledIds, all.reduce(CustomizeEvent.SelectPreset(Preset.Minimal)).disabledIdsOver(emptySet()))
    }
}
