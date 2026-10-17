package io.openshm.patcher.device.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMatcherTest {
    private val matcher = TextMatcher(settingsResolver)
    private val buildNumber = Target(listOf("build_number"), listOf("Build number", "Número de compilación"))

    @Test fun normalizationIgnoresCaseAccentsSpacesAndTrailingPunctuation() {
        assertEquals("numero de compilacion", TextMatcher.normalize("  Número   de COMPILACIÓN… "))
        assertEquals("allow from this source", TextMatcher.normalize("Allow from this source."))
        assertEquals("don't", TextMatcher.normalize("Don’t"))
        assertEquals("", TextMatcher.normalize(" … "))
    }

    @Test fun matchesTextAndContentDescriptionRegardlessOfAccent() {
        val byText = screen { row("NUMERO DE COMPILACION") }
        assertNotNull(matcher.find(byText, buildNumber).match)
        val byDescription = screen { node(description = "Número de compilación", clickable = true) }
        assertNotNull(matcher.find(byDescription, buildNumber).match)
    }

    @Test fun settingsResourceIsExactAndFallbackIsNot() {
        val screen = screen { row("Build number") }
        assertTrue(matcher.find(screen, buildNumber).match!!.exact)
        // Without the Settings resource only the bundled fallback matches, and it is never exact.
        assertFalse(TextMatcher(noResolver).find(screen, buildNumber).match!!.exact)
    }

    @Test fun resourceStringInTheUsersLanguageWinsOverEnglishFallbacks() {
        val spanish = TextMatcher { if (it == "build_number") "Número de compilación" else null }
        val screen = screen { row("Número de compilación") }
        assertTrue(spanish.find(screen, buildNumber).match!!.exact)
        assertEquals("Número de compilación", spanish.label(buildNumber))
        assertEquals("Build number", TextMatcher(noResolver).label(buildNumber))
    }

    @Test fun runTimeExactLabelIsExactAndIsTheLabel() {
        val app = Target(emptyList(), emptyList(), exactLabels = listOf("LibreWrist"))
        assertTrue(TextMatcher(noResolver).find(screen { row("LibreWrist") }, app).match!!.exact)
        assertEquals("LibreWrist", TextMatcher(noResolver).label(app))
    }

    @Test fun viewIdIsExactAndMatchesTheShortName() {
        val target = Target(emptyList(), listOf("Allow"), viewIds = listOf("android:id/button1"), role = Role.Button)
        val screen = screen { node(text = "Whatever the OEM says", viewId = "android:id/button1", clickable = true) }
        val match = TextMatcher(noResolver).find(screen, target).match
        assertTrue(match!!.exact)
    }

    @Test fun substringMatchIsNotExact() {
        val screen = screen { row("Build number and version") }
        val match = matcher.find(screen, buildNumber).match
        assertNotNull(match)
        assertFalse(match!!.exact)
    }

    @Test fun equalTextBeatsSubstringOfAnotherRow() {
        val system = Target(listOf("header_category_system"), listOf("System"))
        val screen = screen {
            row("System updates")
            row("System")
        }
        val result = matcher.find(screen, system)
        assertEquals("System", result.match!!.node.text)
        assertFalse(result.ambiguous)
    }

    @Test fun twoRowsWithTheSameLabelAreAmbiguous() {
        val screen = screen {
            row("System")
            row("System")
        }
        val result = matcher.find(screen, Target(listOf("header_category_system"), listOf("System")))
        assertTrue(result.ambiguous)
        assertNotNull(result.match)
    }

    @Test fun titleAndSummaryInTheSameRowAreNotAmbiguous() {
        val screen = Screen()
        val row = screen.row("Wireless debugging")
        screen.node(text = "Wireless debugging is on", parent = row.containerId)
        val result = matcher.find(screen.snapshot(), Target(listOf("enable_adb_wireless"), listOf("Wireless debugging")))
        assertFalse(result.ambiguous)
        assertEquals(row.containerId, result.match!!.clickNode.id)
    }

    @Test fun invisibleNodesNeverMatch() {
        val screen = screen { row("Build number", visible = false) }
        assertNull(matcher.find(screen, buildNumber).match)
    }

    @Test fun nothingMatchesAnEmptyScreen() {
        val result = matcher.find(screen { }, buildNumber)
        assertNull(result.match)
        assertFalse(result.ambiguous)
    }

    @Test fun returnsTheNearestClickableAncestor() {
        val s = Screen()
        val row = s.row("Build number")
        val match = matcher.find(s.snapshot(), buildNumber).match!!
        assertEquals(row.textId, match.node.id)
        assertEquals(row.containerId, match.clickNode.id)
        assertEquals(match.clickNode.bounds, match.bounds)
    }

    @Test fun clickableTextIsItsOwnClickNode() {
        val s = Screen()
        val id = s.node(text = "Build number", clickable = true)
        val match = matcher.find(s.snapshot(), buildNumber).match!!
        assertEquals(id, match.clickNode.id)
    }

    @Test fun hugeAncestorKeepsTheTextBoundsForTheHighlight() {
        val s = Screen()
        val container = s.node(clickable = true, bounds = GuideRect(0, 0, 1080, 2400))
        s.node(text = "Build number", parent = container, bounds = GuideRect(40, 300, 600, 360))
        val match = matcher.find(s.snapshot(), buildNumber).match!!
        assertEquals(container, match.clickNode.id)
        assertEquals(GuideRect(40, 300, 600, 360), match.bounds)
    }

    @Test fun wideButShortAncestorKeepsTheAncestorBounds() {
        val s = Screen()
        val row = s.row("Build number")
        val match = matcher.find(s.snapshot(), buildNumber).match!!
        assertEquals(row.containerId, match.clickNode.id)
        assertEquals(GuideRect(0, 300, 1080, 450), match.bounds)
    }

    @Test fun switchRoleReadsTheCheckedStateInsideTheRow() {
        val target = Target(listOf("permit_draw_overlay"), listOf("Allow display over other apps"), role = Role.Switch)
        val on = screen { row("Allow display over other apps", checked = true) }
        val off = screen { row("Allow display over other apps", checked = false) }
        assertEquals(true, matcher.find(on, target).match!!.checked)
        assertEquals(false, matcher.find(off, target).match!!.checked)
    }

    // Android 16's "Wireless debugging": the row opens the page, its own switch (same label) toggles it.
    private fun twoTargetRow() = screen {
        val row = node(clickable = true, bounds = GuideRect(0, 2560, 1080, 2778))
        node(text = "Wireless debugging", parent = row, bounds = GuideRect(96, 2608, 516, 2673))
        val frame = node(parent = row, bounds = GuideRect(900, 2560, 1060, 2778))
        node(
            description = "Wireless debugging", className = "android.widget.Switch", clickable = true, checkable = true,
            parent = frame, bounds = GuideRect(900, 2597, 1060, 2741),
        )
    }

    @Test fun twoTargetRowIsOneRowAndTapsTheRow() {
        val result = matcher.find(twoTargetRow(), Target(listOf("enable_adb_wireless"), listOf("Wireless debugging")))
        assertFalse(result.ambiguous)
        assertEquals(1, result.match!!.clickNode.id)
    }

    @Test fun twoTargetRowTogglesTheSwitchForTheSwitchRole() {
        val target = Target(listOf("enable_adb_wireless"), listOf("Wireless debugging"), role = Role.Switch)
        val result = matcher.find(twoTargetRow(), target)
        assertFalse(result.ambiguous)
        assertEquals(4, result.match!!.clickNode.id)
    }

    @Test fun buttonRoleNeedsAClickableNode() {
        val target = Target(emptyList(), listOf("Allow"), role = Role.Button)
        val label = screen { node(text = "Allow") }
        assertNull(TextMatcher(noResolver).find(label, target).match)
        val button = screen { node(text = "Allow", className = "android.widget.Button") }
        assertNotNull(TextMatcher(noResolver).find(button, target).match)
    }
}
