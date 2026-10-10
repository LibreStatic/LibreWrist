package io.openshm.patcher.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Alarm-fork patterns that need no vendor APK (synthetic manifest), from the real catalogue. */
class AlarmOpsTest {
    private val cat = Catalogue.parse(File(System.getProperty("shm.repoRoot"), "catalogue/catalogue.json").readText())
    private val pkg = "com.samsung.android.watch.alarm"
    private val manifest = """<manifest xmlns:android="$ANDROID_NS" package="$pkg">
    <application android:label="@string/app_name">
        <activity android:label="@string/app_name" android:name="$pkg.activity.AlarmMainActivity">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
            </intent-filter>
        </activity>
    </application>
</manifest>"""

    private fun run(f: Fixture, id: String) = cat.runner("com.example").run(f.ctx, listOf(id))

    private fun fixture(strings: Map<String, MutableMap<String, String>>? = null) =
        Fixture(manifest = DomManifest(manifest), resources = strings?.let { MapResources(it) })

    @Test fun hideLauncherKeepsActivityAndOtherFilters() {
        val f = fixture()
        run(f, "alarm-hide-launcher")
        val m = f.manifest!!.text()
        assertFalse("LAUNCHER" in m)
        assertFalse("android.intent.action.MAIN" in m)
        assertTrue("AlarmMainActivity" in m)
        assertTrue("android.intent.action.VIEW" in m)
    }

    @Test fun hideLauncherFailsWhenAnchorMissing() {
        val f = fixture()
        run(f, "alarm-hide-launcher")
        try {
            run(f, "alarm-hide-launcher")
            fail()
        } catch (e: PatternRequiredException) {
            // expected: the second run finds no launcher filter
        }
    }

    @Test fun relabel() {
        val f = fixture(mapOf("" to mutableMapOf(), "-es" to mutableMapOf()))
        run(f, "alarm-relabel")
        val m = f.manifest!!.text()
        assertEquals(2, Regex("@string/interop_app_label").findAll(m).count())
        assertFalse("@string/app_name" in m)
        assertEquals("Phone alarms", f.resources!!.string("", "interop_app_label"))
        assertEquals("Alarmas del teléfono", f.resources!!.string("-es", "interop_app_label"))
    }

    @Test fun relabelNameCollisionFails() {
        val f = fixture(mapOf("" to mutableMapOf("interop_app_label" to "x"), "-es" to mutableMapOf()))
        try {
            run(f, "alarm-relabel")
            fail()
        } catch (e: PatternRequiredException) {
            // expected
        }
    }

    @Test fun pinnedPatternsFailOnForeignTree() {
        for (id in listOf("alarm-rebrand-code", "alarm-semgesture-skip", "alarm-link-send",
            "alarm-components-prune", "alarm-permissions-prune")) {
            try {
                run(fixture(), id)
                fail(id)
            } catch (e: PatternRequiredException) {
                // expected
            }
        }
    }

    @Test fun familyDetection() {
        assertEquals("alarm-watch", cat.detectFamily(pkg, MemorySmaliTree()))
    }

    @Test fun profileIdsExistAndOrder() {
        val ids = cat.profile("alarm").families.getValue("alarm-watch")
        assertTrue(ids.all { it in cat.patterns })
        assertEquals(listOf("alarm-hide-launcher", "alarm-relabel"), ids.takeLast(2))
        assertEquals(ids.size, ids.toSet().size)
    }
}
