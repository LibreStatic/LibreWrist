package io.openshm.patcher

import io.openshm.patcher.build.BuiltBuild
import io.openshm.patcher.build.BuiltRecord
import io.openshm.patcher.build.buildFacts
import io.openshm.patcher.build.buildKey
import io.openshm.patcher.build.outputFingerprint
import io.openshm.patcher.data.AppCatalogue
import io.openshm.patcher.data.Automation
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.OutputApp
import io.openshm.patcher.data.SessionState
import io.openshm.patcher.data.effectiveChinaMode
import io.openshm.patcher.data.requestedRebuildScope
import io.openshm.patcher.data.sourceInputId
import io.openshm.patcher.ui.screens.home.updateOfferFor
import java.io.ByteArrayInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** China mode swaps the official app Samsung Health+ is acquired and built from (`chinaFrom`); the output stays the same. */
class ChinaSourceTest {
    private val catalogue = TestCatalogue.load()

    @Before fun install() = AppCatalogue.install(catalogue)

    @After fun resetChinaMode() = Automation.setChinaMode(false)

    private fun app(id: String, input: String?, china: String? = null) =
        OutputApp(id, id, Device.Phone, "x.$id", input, chinaInputId = china)

    @Test fun theResolverPicksTheChinaInputOnlyWhenTheOutputHasOne() {
        val shealth = catalogue.output("shealth-phone")
        assertEquals("shealth-phone", shealth.inputId)
        assertEquals("shealth-phone-cn", shealth.chinaInputId)
        assertEquals("shealth-phone", shealth.sourceInputId(false))
        assertEquals("shealth-phone-cn", shealth.sourceInputId(true))
        for (id in listOf("shm-phone", "shm-watch", "alarm-fork")) {
            val out = catalogue.output(id)
            assertNull(out.chinaInputId)
            assertEquals(out.inputId, out.sourceInputId(true))
        }
        assertNull(catalogue.output("bridge-phone").sourceInputId(true))
    }

    @Test fun theEffectiveFlagFollowsTheUsersChoice() {
        assertFalse(effectiveChinaMode()) // nothing detected in a unit test
        Automation.setChinaMode(true)
        assertTrue(effectiveChinaMode())
        Automation.setChinaMode(false)
        assertFalse(effectiveChinaMode())
    }

    @Test fun acquireNeedsTheChinaInputInsteadOfTheGlobalOne() {
        val session = SessionState()
        assertEquals(listOf("shm-phone", "shm-watch", "alarm-watch", "shealth-phone"), session.inputIds(false))
        assertEquals(listOf("shm-phone", "shm-watch", "alarm-watch", "shealth-phone-cn"), session.inputIds(true))
        assertEquals(session.inputIds(false), session.inputIds)
        Automation.setChinaMode(true)
        assertEquals(session.inputIds(true), session.inputIds)
        // the output list does not change
        assertEquals(session.outputIds, session.outputIds)
        assertTrue("shealth-phone" in session.outputIds)
    }

    @Test fun skippingTheAppDropsItsChinaInputToo() {
        val session = SessionState(skippedApps = setOf("samsung-health"))
        assertTrue(session.inputIds(true).none { it.startsWith("shealth-phone") })
    }

    @Test fun theChinaInputOffersOnlyThePatchesOfItsProfile() {
        val global = catalogue.patches("shealth-phone").map { it.id }
        val china = catalogue.patches("shealth-phone-cn").map { it.id }
        assertTrue("shealth-oobe-local" in global && "shealth-china-account-gate" in global)
        assertTrue("shealth-oobe-local" !in china && "shealth-china-account-gate" !in china)
        assertEquals(global - setOf("shealth-oobe-local", "shealth-china-account-gate"), china)
        assertTrue(catalogue.patternCount(listOf("shealth-phone-cn")) < catalogue.patternCount(listOf("shealth-phone")))
    }

    @Test fun rebuildScopeGroupsByTheInputOfTheMode() {
        val outputs = listOf(app("a", "in1", china = "in1-cn"), app("b", "in1"), app("c", "in1-cn"))
        val all = listOf("a", "b", "c")
        assertEquals(setOf("a", "b"), requestedRebuildScope(outputs, "a", all))
        assertEquals(setOf("a", "c"), requestedRebuildScope(outputs, "a", all, chinaMode = true))
        assertEquals(setOf("shealth-phone"), requestedRebuildScope(catalogue.outputs, "shealth-phone", SessionState().outputIds, true))
    }

    @Test fun theBuildDependsOnTheSource() {
        val ids = listOf("shm-phone", "shealth-phone")
        val global = buildFacts(catalogue, ids, chinaMode = false)
        val china = buildFacts(catalogue, ids, chinaMode = true)
        assertTrue("input.shealth-phone" in global && "input.shealth-phone-cn" !in global)
        assertTrue("input.shealth-phone-cn" in china && "input.shealth-phone" !in china)
        assertEquals(global, buildFacts(catalogue, ids))
        val open = { path: String -> ByteArrayInputStream("asset:$path".toByteArray()) }
        fun fp(id: String, china: Boolean) = outputFingerprint(catalogue, id, "com.example", emptyList(), "aa", "e1", "bb", open, china)
        assertNotEquals(fp("shealth-phone", false), fp("shealth-phone", true))
        assertEquals(fp("shm-phone", false), fp("shm-phone", true))
        assertNotEquals(buildKey(1, "p", ids), buildKey(1, "p", ids, chinaMode = true))
        assertEquals(buildKey(1, "p", ids), buildKey(1, "p", ids, chinaMode = false))
    }

    @Test fun switchingChinaModeCountsAsNeedingARebuild() {
        val ids = listOf("shm-phone", "shealth-phone")
        fun built(china: Boolean) =
            BuiltBuild("k", "p", catalogue.serial, 0, listOf(BuiltRecord("shealth-phone", "x", 1, "ff")), buildFacts(catalogue, ids, china))
        // built from the global input, China mode now on (and the other way round): rebuild offered at the same serial
        val toChina = updateOfferFor(catalogue, built(false), ids, chinaMode = true)
        assertNotNull(toChina)
        assertEquals(catalogue.input("shealth-phone-cn").label, toChina!!.appLabel)
        assertEquals(catalogue.input("shealth-phone").versionName, toChina.fromVersion)
        assertEquals(catalogue.input("shealth-phone-cn").versionName, toChina.toVersion)
        assertFalse(toChina.patchesOnly)
        val toGlobal = updateOfferFor(catalogue, built(true), ids, chinaMode = false)
        assertEquals(catalogue.input("shealth-phone").label, toGlobal!!.appLabel)
        // the build matches the mode: nothing to do
        assertNull(updateOfferFor(catalogue, built(false), ids, chinaMode = false))
        assertNull(updateOfferFor(catalogue, built(true), ids, chinaMode = true))
        // an app without a China source never triggers it
        assertNull(updateOfferFor(catalogue, built(false), listOf("shm-phone"), chinaMode = true))
    }
}
