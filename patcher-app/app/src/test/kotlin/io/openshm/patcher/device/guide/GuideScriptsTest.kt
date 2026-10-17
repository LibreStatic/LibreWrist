package io.openshm.patcher.device.guide

import io.openshm.patcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideScriptsTest {
    private val off = GuideEnv(developerOptionsEnabled = false, debuggingDisallowed = false)
    private val on = GuideEnv(developerOptionsEnabled = true, debuggingDisallowed = false)

    private fun GuideScript.ids() = steps.map { it.id }
    private fun GuideScript.step(id: String) = steps.first { it.id == id }

    private fun allScripts(): List<GuideScript> =
        listOf(
            GuideScripts.developerOptionsUnlock(on),
            GuideScripts.wirelessDebugging(off),
            GuideScripts.wirelessDebugging(on),
            GuideScripts.playServicesClearData(),
        ) + PermissionKind.entries.map { GuideScripts.permission(it, "Notifications") }

    @Test fun wirelessDebuggingStartsAtDeveloperOptionsWhenTheyAreOn() {
        val ids = GuideScripts.wirelessDebugging(on).ids()
        assertEquals("developer-options-switch", ids.first())
        assertEquals(
            listOf("wireless-debugging", "wireless-switch", "wireless-allow", "pair-with-code", "read-code"),
            ids.drop(1),
        )
    }

    @Test fun wirelessDebuggingPrependsTheUnlockWhenDeveloperOptionsAreOff() {
        val unlock = GuideScripts.developerOptionsUnlock(off).ids()
        val ids = GuideScripts.wirelessDebugging(off).ids()
        assertEquals(unlock, ids.take(unlock.size))
        assertEquals(GuideScripts.wirelessDebugging(on).ids(), ids.drop(unlock.size))
    }

    @Test fun unlockTapsBuildNumberSevenTimesAndStopsOnTheToast() {
        val build = GuideScripts.developerOptionsUnlock(on).step("build-number")
        assertEquals(7, build.taps)
        assertEquals(Actor.Tic, build.actor)
        val toast = build.done as DoneWhen.Toast
        assertEquals(listOf("show_dev_on", "show_dev_already"), toast.settingsRes)
        assertTrue("¡Ya eres desarrollador!" in toast.fallbacks)
        assertTrue("No need, you are already a developer." in toast.fallbacks)
        assertTrue("build_number" in build.target!!.settingsRes)
    }

    @Test fun unlockHasOemRoutesAndTheCredentialIsTheUsers() {
        val script = GuideScripts.developerOptionsUnlock(on)
        assertTrue(script.step("software-info").optional)
        assertTrue("MIUI version" in script.step("build-number").target!!.fallbacks)
        val pin = script.step("pin")
        assertEquals(StepKind.WaitForUser, pin.kind)
        assertEquals(Actor.User, pin.actor)
        val order = script.ids()
        assertTrue(order.indexOf("build-number") < order.indexOf("pin"))
        assertTrue(order.indexOf("pin") < order.indexOf("open-developer-options"))
        // About phone was opened from LibreWrist: Back would leave Settings, so Tic opens Developer options itself.
        assertEquals(StepKind.OpenDeveloperOptions, script.step("open-developer-options").kind)
        assertEquals("open-developer-options", order.last())
    }

    @Test fun debuggingDisallowedIsASingleBlockedStep() {
        val env = GuideEnv(developerOptionsEnabled = false, debuggingDisallowed = true)
        for (script in listOf(GuideScripts.developerOptionsUnlock(env), GuideScripts.wirelessDebugging(env))) {
            assertEquals(1, script.steps.size)
            assertEquals(StepKind.Blocked, script.steps.single().kind)
            assertEquals(R.string.guide_debugging_disallowed, script.steps.single().bubble)
        }
    }

    @Test fun securityAndDestructiveStepsAreTheUsers() {
        val wireless = GuideScripts.wirelessDebugging(on)
        assertEquals(Actor.User, wireless.step("wireless-allow").actor)
        assertEquals(listOf("android:id/button1"), wireless.step("wireless-allow").target!!.viewIds)
        assertTrue("Allow" in wireless.step("wireless-allow").target!!.fallbacks)
        assertTrue("Permitir" in wireless.step("wireless-allow").target!!.fallbacks)

        val gms = GuideScripts.playServicesClearData()
        assertEquals(Actor.Tic, gms.step("storage").actor)
        assertEquals(Actor.Tic, gms.step("manage-space").actor)
        assertEquals(Actor.User, gms.step("clear-all-data").actor)
        assertEquals(Actor.User, gms.step("clear-all-data-ok").actor)

        val listener = GuideScripts.permission(PermissionKind.ListenerDetail)
        assertEquals(Actor.Tic, listener.step("listener-switch").actor)
        assertEquals(Actor.User, listener.step("listener-confirm").actor)

        val restricted = GuideScripts.permission(PermissionKind.RestrictedSettings)
        assertTrue(restricted.steps.filter { it.kind != StepKind.Back }.all { it.actor == Actor.User })
    }

    @Test fun noTicStepTargetsAClearOrConfirmButton() {
        val destructive = setOf(
            "clear all data", "borrar todos los datos", "clear storage", "borrar almacenamiento", "clear data", "borrar datos",
            "allow restricted settings",
        )
        for (script in allScripts()) {
            for (step in script.steps.filter { it.actor == Actor.Tic }) {
                val labels = step.target?.fallbacks.orEmpty().map(TextMatcher::normalize)
                assertTrue("${script.id}/${step.id}", labels.none { it in destructive })
                assertTrue("${script.id}/${step.id}", step.target?.role != Role.Button)
            }
        }
    }

    @Test fun permissionsToggleWithTheSettingsStringsAndGoBack() {
        val overlay = GuideScripts.permission(PermissionKind.Overlay)
        assertEquals(StepKind.Toggle, overlay.step("overlay-switch").kind)
        assertEquals(listOf("permit_draw_overlay"), overlay.step("overlay-switch").target!!.settingsRes)
        assertEquals(StepKind.Back, overlay.steps.last().kind)

        val unknown = GuideScripts.permission(PermissionKind.UnknownSources)
        assertEquals(listOf("external_source_switch_title"), unknown.step("unknown-sources-switch").target!!.settingsRes)

        val battery = GuideScripts.permission(PermissionKind.Battery)
        assertTrue("Unrestricted" in battery.step("battery-unrestricted").target!!.fallbacks)

        val app = GuideScripts.permission(PermissionKind.AppDetails, "Notifications")
        assertEquals(Actor.User, app.steps.single().actor)
        assertTrue("Notifications" in app.steps.single().target!!.fallbacks)
    }

    @Test fun overlayAndBatteryOpenTheAppRowFirstWhenTheAppIsKnown() {
        for (kind in listOf(PermissionKind.Overlay, PermissionKind.Battery)) {
            val first = GuideScripts.permission(kind, appLabel = "LibreWrist").steps.first()
            assertEquals(Actor.Tic, first.actor)
            assertEquals(listOf("LibreWrist"), first.target!!.exactLabels)
            assertTrue(GuideScripts.permission(kind).steps.first().target!!.exactLabels.isEmpty())
        }
    }

    @Test fun playServicesUsesTheStorageStrings() {
        val gms = GuideScripts.playServicesClearData()
        assertTrue("storage_settings_for_app" in gms.step("storage").target!!.settingsRes)
        assertTrue("manage_space_text" in gms.step("manage-space").target!!.settingsRes)
        assertEquals(Actor.User, gms.step("clear-storage").actor)
    }

    @Test fun everyTargetStepHasEnglishAndSpanishFallbacksAndUniqueIds() {
        for (script in allScripts()) {
            assertEquals(script.id, script.steps.map { it.id }.distinct().size, script.steps.size)
            for (step in script.steps) {
                val target = step.target ?: continue
                if (step.id == "app-details-point") continue
                assertTrue("${script.id}/${step.id} needs fallbacks", target.fallbacks.size >= 2)
            }
        }
    }

    @Test fun wirelessDebuggingEndsReadingTheCode() {
        val script = GuideScripts.wirelessDebugging(on)
        assertEquals(StepKind.ReadPairingCode, script.steps.last().kind)
        val pair = script.step("pair-with-code").target!!
        assertTrue("adb_pair_method_code_title" in pair.settingsRes)
        assertTrue("Vincular dispositivo con un código de vinculación" in pair.fallbacks)
        assertEquals(StepKind.Toggle, script.step("wireless-switch").kind)
        assertEquals(DoneWhen.Checked, script.step("wireless-switch").done)
    }
}
