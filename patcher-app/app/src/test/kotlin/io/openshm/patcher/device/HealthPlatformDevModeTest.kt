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
    fun devModeShownAndTitleFoundByViewId() {
        val screen = HealthPlatformDevMode.parseSettingsScreen(TOP_ON)
        assertTrue(screen.devModeShown)
        assertEquals(224 to 43, screen.titleCenter)
    }

    @Test
    fun devModeOffWhenTheSubtitleIsMissingOrHidden() {
        val missing = HealthPlatformDevMode.parseSettingsScreen(TOP_ON.replace(SUBTITLE_LINE, ""))
        assertFalse(missing.devModeShown)
        assertEquals(224 to 43, missing.titleCenter)
        val gone = HealthPlatformDevMode.parseSettingsScreen(TOP_ON.replace("V.ED..... ........ 170", "G.ED..... ........ 170"))
        assertFalse(gone.devModeShown)
    }

    @Test
    fun titleCentreAddsTheParentsOffsets() {
        val shifted = TOP_ON.replace("0,0-450,450 #1020002 android:id/content", "10,20-460,470 #1020002 android:id/content")
        assertEquals(234 to 63, HealthPlatformDevMode.parseSettingsScreen(shifted).titleCenter)
    }

    @Test
    fun otherActivitiesTitlesAreIgnored() {
        val screen = HealthPlatformDevMode.parseSettingsScreen(OTHER_ACTIVITY)
        assertFalse(screen.devModeShown)
        assertNull(screen.titleCenter)
    }

    @Test
    fun emptyOutputReadsAsEmptyScreen() {
        val screen = HealthPlatformDevMode.parseSettingsScreen("")
        assertFalse(screen.devModeShown)
        assertNull(screen.titleCenter)
    }

    private companion object {
        const val SUBTITLE_LINE = "          android.widget.TextView{54b4675 V.ED..... ........ 170,87-279,117 #7f0a0098 app:id/dev_mode_subtitle}\n"
        const val OTHER_ACTIVITY = "TASK 1 id=2\n" +
            "  ACTIVITY com.android.settings/.Settings 1 pid=2\n" +
            "    View Hierarchy:\n" +
            "      DecorView@1[Settings]\n" +
            "        android.widget.FrameLayout{a V.E...... ........ 0,0-450,450 #1020002 android:id/content}\n" +
            "          android.widget.TextView{b VFED..C.. ........ 102,0-347,87 #1020016 android:id/title}\n" +
            "          android.widget.TextView{c V.ED..... ........ 170,87-279,117 #7f0a0098 app:id/dev_mode_subtitle}\n"
        const val TOP_ON = OTHER_ACTIVITY +
            "TASK 2 id=3\n" +
            "  ACTIVITY com.samsung.android.service.health/.wear.settings.home.SettingsMainActivity 2 pid=3\n" +
            "    View Hierarchy:\n" +
            "      DecorView@2[SettingsMainActivity]\n" +
            "        android.widget.FrameLayout{d V.E...... ........ 0,0-450,450 #1020002 android:id/content}\n" +
            "          android.widget.TextView{d64d9ac VFED..C.. ........ 102,0-347,87 #7f0a019c app:id/title}\n" +
            SUBTITLE_LINE +
            "          android.widget.TextView{e V.ED..... ........ 46,348-408,385 #7f0a0090 app:id/current_version}\n"
    }
}
