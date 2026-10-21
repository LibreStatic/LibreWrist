package io.openshm.patcher.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The schema-2 additions: apps, strings, platform, output behaviours, stock replacedBy, grant why. */
class CatalogueSchema2Test {
    private val root = File(System.getProperty("shm.repoRoot"))
    private val original = File(root, "catalogue/catalogue.json").readText()

    private fun parsed() = Catalogue.parse(original)

    private fun mutated(from: String, to: String): String {
        assertTrue("anchor not found: $from", from in original)
        return original.replaceFirst(from, to)
    }

    private fun assertRejected(text: String, expected: String) {
        try {
            Catalogue.parse(text)
            fail("expected a problem containing '$expected'")
        } catch (e: CatalogueException) {
            assertTrue(e.problems.toString(), e.problems.any { expected in it })
        }
    }

    @Test fun committedCatalogueCarriesTheNewData() {
        val c = parsed()
        assertEquals(2, c.schema)
        assertEquals(listOf("health-monitor", "alarm", "bridge", "samsung-health"), c.apps.map { it.id })
        assertEquals(listOf(true, true, true, true), c.apps.map { it.optional })
        assertEquals(listOf("bridge"), c.apps.single { it.id == "alarm" }.requires)
        assertTrue(c.apps.filter { it.id != "alarm" }.all { it.requires.isEmpty() })
        assertEquals(listOf("shm-phone", "shm-watch"), c.apps.first().outputs)
        assertEquals("com.samsung.android.app.watchmanager", c.platform.companionApp.packageName)
        assertEquals(5, c.platform.companionApp.watchPlugins.size)
        assertEquals(10, c.platform.healthPlatform.taps)
        assertEquals(listOf("shm-watch"), c.platform.healthPlatform.requiredBy)
        assertEquals(listOf("com.samsung.android.wear.shealth"), c.platform.logPackages.getValue("watch"))
        assertEquals("doze_always_on", c.platform.revertSettings.single().key)
        val phone = c.outputs.single { it.id == "bridge-phone" }
        assertEquals(".CommsBootReceiver", phone.start?.receiver)
        assertTrue(phone.knownSigner && phone.singleCopy && !phone.companionLink)
        assertEquals("\${PKG_PREFIX}.shmbridge.phone.status", phone.statusAuthority)
        assertTrue(c.outputs.single { it.id == "bridge-watch" }.companionLink)
        assertEquals(mapOf("alarms" to "alarm"), c.outputs.single { it.id == "bridge-watch" }.start?.switches)
        assertEquals(emptyMap<String, String>(), phone.start?.switches)
        assertEquals("shm-watch", c.stockAppsToDisable.single().replacedBy)
        assertTrue(c.grants.getValue("bridge-phone").any { it.why == "grant.why.calls" })
        val en = c.strings.getValue("en")
        assertTrue(en.getValue("output.shm-phone.label").isNotBlank())
        assertEquals(18, c.strings.size)
        for ((loc, table) in c.strings) assertTrue(loc, en.keys.containsAll(table.keys))
    }

    @Test fun unknownIconIsRejected() {
        assertRejected(mutated("\"icon\": \"monitor-heart\"", "\"icon\": \"rocket\""), "icon must be one of")
    }

    @Test fun outputInTwoAppsIsRejected() {
        val text = mutated("\"outputs\": [\n        \"alarm-fork\"\n      ]", "\"outputs\": [\n        \"alarm-fork\",\n        \"shm-phone\"\n      ]")
        assertRejected(text, "output 'shm-phone' is already in app 'health-monitor'")
    }

    @Test fun outputInNoAppIsRejected() {
        val text = mutated("\"outputs\": [\n        \"alarm-fork\"\n      ]", "\"outputs\": [\n        \"bridge-phone\"\n      ]")
        assertRejected(text, "output 'alarm-fork' is in no app")
    }

    private val alarmRequires = "\"icon\": \"alarm\",\n      \"requires\": [\n        \"bridge\"\n      ]"

    @Test fun requiresMustNameAnotherExistingApp() {
        assertRejected(mutated(alarmRequires, alarmRequires.replace("\"bridge\"", "\"nope\"")), "requires unknown app 'nope'")
        assertRejected(mutated(alarmRequires, alarmRequires.replace("\"bridge\"", "\"alarm\"")), "app 'alarm' requires itself")
        assertRejected(mutated(alarmRequires, alarmRequires.replace("\"bridge\"", "\"bridge\", \"bridge\"")), "distinct app ids")
        assertRejected(mutated(alarmRequires, alarmRequires.replace("[\n        \"bridge\"\n      ]", "[]")), "non-empty list")
    }

    @Test fun requiresNeedsOptionalAppsOnBothEnds() {
        val bridgeRequired = mutated("\"optional\": true,\n      \"icon\": \"swap\"", "\"optional\": false,\n      \"icon\": \"swap\"")
        assertRejected(bridgeRequired, "required app 'bridge' must be optional")
        val alarmRequired = mutated("\"optional\": true,\n      \"icon\": \"alarm\"", "\"optional\": false,\n      \"icon\": \"alarm\"")
        assertRejected(alarmRequired, "an app that requires another must be optional")
    }

    @Test fun requiresCycleIsRejected() {
        val cyc = mutated("\"icon\": \"swap\"", "\"icon\": \"swap\",\n      \"requires\": [\n        \"alarm\"\n      ]")
        assertRejected(cyc, "requires has a cycle")
    }

    @Test fun appWithUnknownOutputIsRejected() {
        assertRejected(mutated("\"outputs\": [\n        \"alarm-fork\"\n      ]", "\"outputs\": [\n        \"nope\"\n      ]"),
            "unknown output 'nope'")
    }

    @Test fun missingEnglishRequiredKeyIsRejected() {
        val text = mutated("      \"app.alarm.title\": \"Phone alarms on the watch\",\n", "")
        assertRejected(text, "missing required key 'app.alarm.title'")
        assertRejected(mutated("      \"output.shm-phone.label\":", "      \"output.shm-phone.labell\":"),
            "missing required key 'output.shm-phone.label'")
    }

    @Test fun unknownWhyKeyIsRejected() {
        assertRejected(mutated("\"why\": \"grant.why.bluetooth_phone\"", "\"why\": \"grant.why.nope\""),
            "why 'grant.why.nope' is not in strings/en")
    }

    @Test fun replacedByUnknownOutputIsRejected() {
        assertRejected(mutated("\"replacedBy\": \"shm-watch\"", "\"replacedBy\": \"nope\""), "replacedBy 'nope' is not an output id")
    }

    @Test fun localeKeyMissingFromEnglishIsRejected() {
        assertRejected(mutated("\"es\": {", "\"es\": {\n      \"bogus.key\": \"x\","), "key 'bogus.key' is not in strings/en")
    }

    @Test fun badStringValuesAreRejected() {
        assertRejected(mutated("\"es\": {", "\"es\": {\n      \"inject.watch-mod.title\": \"\","), "single-line string")
        assertRejected(mutated("\"es\": {", "\"es\": {\n      \"inject.watch-mod.title\": \"a\\nb\","), "single-line string")
        assertRejected(mutated("\"es\": {", "\"es\": {\n      \"inject.watch-mod.title\": 5,"), "single-line string")
        assertRejected(mutated("\"es\": {", "\"es\": {\n      \"Bad Key\": \"x\","), "must match")
    }

    @Test fun badLocaleTagIsRejected() {
        assertRejected(mutated("\"es\": {", "\"es_ES\": {"), "locale 'es_ES'")
    }

    @Test fun stringsWithoutEnglishAreRejected() {
        assertRejected(mutated("\"en\": {", "\"xx\": {"), "non-empty \"en\" table")
    }

    @Test fun badOutputBehavioursAreRejected() {
        assertRejected(mutated("\"receiver\": \".BridgeAlarmReceiver\"", "\"receiver\": \"BridgeAlarmReceiver\""), "start.receiver")
        assertRejected(mutated("\"singleCopy\": true", "\"singleCopy\": \"yes\""), "singleCopy must be a boolean")
        assertRejected(mutated("\"statusAuthority\": \"\${PKG_PREFIX}", "\"statusAuthority\": \"\${OTHER}"), "statusAuthority")
        assertRejected(mutated("\"action\": \"\${PKG_PREFIX}.shmbridge.START\"", "\"action\": \"\${PKG_PREFIX}.a b\""), "start.action")
    }

    @Test fun badStartSwitchesAreRejected() {
        val ok = "\"alarms\": \"alarm\""
        assertRejected(mutated(ok, "\"Alarms\": \"alarm\""), "not a valid extra name")
        assertRejected(mutated(ok, "\"1a\": \"alarm\""), "not a valid extra name")
        assertRejected(mutated(ok, "\"alarms\": \"nope\""), "id of an optional app")
        assertRejected(mutated(ok, "\"alarms\": 5"), "id of an optional app")
        assertRejected(mutated("\"switches\": {", "\"other\": 1,\n        \"switches\": {"), "optionally \"switches\"")
    }

    @Test fun badPlatformIsRejected() {
        assertRejected(mutated("\"taps\": 10", "\"taps\": 0"), "taps")
        assertRejected(mutated("\"requiredBy\": [\n        \"shm-watch\"", "\"requiredBy\": [\n        \"nope\""), "requiredBy")
        assertRejected(mutated("\"titleViewId\": \"app:id/title\"", "\"titleViewId\": \"title\""), "titleViewId")
        assertRejected(mutated("\"default\": \"1\"", "\"default\": \"--user\""), "default")
        assertRejected(mutated("\"watch\": [\n        \"com.samsung.android.wear.shealth\"", "\"watch\": [\n        \"not a package\""), "logPackages.watch")
    }

    @Test fun platformPlaceholdersAreNotExpandedInTheModel() {
        val c = parsed()
        assertFalse(c.outputs.any { o -> o.start?.action?.let { "com.example" in it } == true })
        assertNotEquals(0, c.outputs.count { it.start != null })
    }
}
