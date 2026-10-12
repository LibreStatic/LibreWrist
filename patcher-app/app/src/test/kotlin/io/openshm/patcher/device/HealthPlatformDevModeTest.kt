package io.openshm.patcher.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPlatformDevModeTest {

    @Test
    fun healthPlatformInFrontWhenItsActivityIsTopResumed() {
        val out = "    topResumedActivity=ActivityRecord{201713073 u0 " +
            "com.samsung.android.service.health/.wear.settings.home.SettingsMainActivity t8364}\n"
        assertTrue(HealthPlatformDevMode.isHealthPlatformInFront(out))
    }

    @Test
    fun otherAppsInFrontAreIgnored() {
        val out = "    topResumedActivity=ActivityRecord{232446917 u0 " +
            "com.samsung.android.mediacontroller/.view.control.MediaControlActivity t8365}\n"
        assertFalse(HealthPlatformDevMode.isHealthPlatformInFront(out))
        assertFalse(HealthPlatformDevMode.isHealthPlatformInFront(""))
    }

    @Test
    fun similarlyNamedPackagesDoNotCount() {
        val out = "topResumedActivity=ActivityRecord{1 u0 com.samsung.android.service.healthx/.Main t1}"
        assertFalse(HealthPlatformDevMode.isHealthPlatformInFront(out))
    }

    @Test
    fun devModeShownAndTitleFoundByResourceId() {
        val screen = HealthPlatformDevMode.parseSettingsScreen(DUMP_ON)
        assertTrue(screen.devModeShown)
        assertEquals(224 to 43, screen.titleCenter)
    }

    @Test
    fun devModeOffWhenTheSubtitleIsMissing() {
        val screen = HealthPlatformDevMode.parseSettingsScreen(DUMP_ON.replace(SUBTITLE_NODE, ""))
        assertFalse(screen.devModeShown)
        assertEquals(224 to 43, screen.titleCenter)
    }

    @Test
    fun languageDoesNotMatter() {
        val screen = HealthPlatformDevMode.parseSettingsScreen(DUMP_ON.replace("Plataforma de Salud", "Health Platform"))
        assertTrue(screen.devModeShown)
        assertEquals(224 to 43, screen.titleCenter)
    }

    @Test
    fun failedDumpReadsAsEmptyScreen() {
        val screen = HealthPlatformDevMode.parseSettingsScreen("ERROR: null root node returned by UiTestAutomationBridge.")
        assertFalse(screen.devModeShown)
        assertNull(screen.titleCenter)
    }

    private companion object {
        const val SUBTITLE_NODE = """<node index="1" text="[Dev mode]" resource-id="com.samsung.android.service.health:id/dev_mode_subtitle" class="android.widget.TextView" clickable="false" bounds="[170,87][279,117]" />"""
        const val DUMP_ON = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">""" +
            """<node index="0" text="" resource-id="android:id/content" class="android.widget.FrameLayout" bounds="[0,0][450,450]">""" +
            """<node index="0" text="Plataforma de Salud" resource-id="com.samsung.android.service.health:id/title" class="android.widget.TextView" clickable="true" bounds="[102,0][347,87]" />""" +
            SUBTITLE_NODE +
            """<node index="2" text="Versión 1.7.00.05" resource-id="com.samsung.android.service.health:id/current_version" class="android.widget.TextView" bounds="[46,348][408,385]" />""" +
            """</node></hierarchy>"""
    }
}
