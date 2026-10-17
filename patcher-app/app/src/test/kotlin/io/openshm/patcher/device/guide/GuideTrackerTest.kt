package io.openshm.patcher.device.guide

import io.openshm.patcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTrackerTest {
    private val settings = setOf("com.android.settings")
    private val systemTarget = Target(listOf("header_category_system"), listOf("System"))
    private val devOptionsTarget = Target(listOf("development_settings_title"), listOf("Developer options"))

    private fun tap(id: String, target: Target, bubble: Int, actor: Actor = Actor.Tic, taps: Int = 1, done: DoneWhen = DoneWhen.NextTarget) =
        GuideStep(id, target, actor, bubble, done = done, taps = taps, expectedPackages = settings)

    private fun script(vararg steps: GuideStep) = GuideScript("test", steps.toList())

    private fun tracker(script: GuideScript, resolver: LabelResolver = settingsResolver, env: GuideEnv = envOk) =
        GuideTracker(script, resolver, env)

    private val systemScreen get() = Screen().also { it.row("System") }
    private val twoSteps = script(
        tap("system", systemTarget, R.string.guide_tic_opens),
        tap("devopts", devOptionsTarget, R.string.guide_pair_tap),
    )

    /** Ticks from [from] to [to] and returns every action with the time it was emitted. */
    private fun GuideTracker.drive(from: Long, to: Long, stepMs: Long = 50): List<Pair<Long, GuideAction>> {
        val out = mutableListOf<Pair<Long, GuideAction>>()
        var t = from
        while (t <= to) {
            onTick(t).action?.let { out += t to it }
            t += stepMs
        }
        return out
    }

    @Test fun ticTapsWithTheMockTimings() {
        val screen = systemScreen
        val tracker = tracker(twoSteps)
        val snap = screen.snapshot()
        val first = tracker.onSnapshot(snap, 0)
        assertEquals(PetPlacement.NearTarget, first.ui.placement)
        assertEquals(ControlStrip.Controlling, first.ui.strip)
        assertEquals(Actor.Tic, first.ui.bubble!!.actor)
        assertFalse(first.ui.highlight)
        assertFalse(first.ui.reach)
        assertNotNull(first.ui.target)
        assertEquals(PetMood.Talk, first.ui.mood)

        assertFalse(tracker.onTick(3599).ui.reach)
        assertTrue(tracker.onTick(3600).ui.reach)
        assertEquals(0, tracker.onTick(4149).ui.pressSeq)
        val press = tracker.onTick(4150)
        assertEquals(1, press.ui.pressSeq)
        assertNull(tracker.onTick(4499).action)

        val click = tracker.onTick(4500)
        val row = snap.nodes.first { it.text == "System" }.parentId
        assertEquals(GuideAction.Click(row!!), click.action)
        assertEquals(1, click.ui.pressSeq)
        assertEquals(PetMood.Point, click.ui.mood)
        assertNull(tracker.onTick(4550).action)
    }

    @Test fun aFirstTickAfterTheClickTimeStillPressesAndClicksOnce() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        val late = tracker.onTick(5000)
        assertTrue(late.action is GuideAction.Click)
        assertEquals(1, late.ui.pressSeq)
    }

    @Test fun unsureFallsBackToTheUserAndNeverClicks() {
        // Only the bundled fallback label matches: not exact.
        val tracker = tracker(twoSteps, resolver = noResolver)
        val out = tracker.onSnapshot(systemScreen.snapshot(), 0)
        assertEquals(Actor.User, out.ui.bubble!!.actor)
        assertEquals(R.string.guide_user_taps, out.ui.bubble!!.text)
        assertTrue(out.ui.highlight)
        assertFalse(out.ui.reach)
        assertEquals(PetPlacement.Corner, out.ui.placement)
        assertEquals(ControlStrip.Hidden, out.ui.strip)
        assertTrue(tracker.drive(0, 20_000).isEmpty())
    }

    @Test fun twoCandidatesFallBackToTheUser() {
        val s = Screen()
        s.row("System")
        s.row("System")
        val tracker = tracker(twoSteps)
        val out = tracker.onSnapshot(s.snapshot(), 0)
        assertTrue(out.ui.highlight)
        assertTrue(tracker.drive(0, 5_000).isEmpty())
    }

    @Test fun anUnexpectedPackageFallsBackToTheUserAfterAShortGrace() {
        val s = Screen(pkg = "com.example.other")
        s.row("System")
        val tracker = tracker(twoSteps)
        val early = tracker.onSnapshot(s.snapshot(), 0)
        assertFalse(early.ui.highlight)
        assertTrue(tracker.drive(0, 600).isEmpty())
        val late = tracker.onSnapshot(s.snapshot(), 800)
        assertTrue(late.ui.highlight)
        assertTrue(tracker.drive(800, 10_000).isEmpty())
    }

    @Test fun aWrongClassHintFallsBackToTheUser() {
        val hinted = script(
            GuideStep("system", systemTarget, Actor.Tic, R.string.guide_tic_opens, expectedPackages = settings, expectedClassHint = "Settings\$Home"),
        )
        val tracker = tracker(hinted)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        val out = tracker.onSnapshot(systemScreen.snapshot(), 1000)
        assertTrue(out.ui.highlight)
    }

    @Test fun aClickWithoutVisibleEffectHandsTheStepToTheUser() {
        val tracker = tracker(twoSteps)
        val snap = systemScreen.snapshot()
        tracker.onSnapshot(snap, 0)
        assertTrue(tracker.onTick(4500).action is GuideAction.Click)
        assertEquals(Actor.Tic, tracker.onSnapshot(snap, 6499).ui.bubble!!.actor)
        val out = tracker.onSnapshot(snap, 6500)
        assertEquals(Actor.User, out.ui.bubble!!.actor)
        assertTrue(out.ui.highlight)
        assertFalse(out.ui.reach)
        assertEquals(ControlStrip.Hidden, out.ui.strip)
        assertEquals(PetPlacement.Corner, out.ui.placement)
        assertTrue(tracker.drive(6500, 20_000).isEmpty())
    }

    @Test fun aWindowChangeAfterTheClickAdvancesTheStep() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        tracker.onTick(4500)
        val next = Screen(cls = "Elsewhere")
        next.row("Something else")
        val out = tracker.onSnapshot(next.snapshot(), 4800)
        assertEquals(R.string.guide_pair_tap, out.ui.bubble!!.text)
    }

    @Test fun theStepAdvancesWhenTheNextTargetAppears() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        val next = Screen()
        next.row("Developer options")
        val out = tracker.onSnapshot(next.snapshot(), 300)
        assertEquals(R.string.guide_pair_tap, out.ui.bubble!!.text)
    }

    @Test fun aLookAlikeTitleWithTheNextTargetMovesOnWithoutHandingOver() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        // The page Tic opened repeats the label in a title that cannot be tapped.
        val page = Screen()
        page.node(text = "System")
        page.row("Developer options")
        val out = tracker.onSnapshot(page.snapshot(), 300)
        assertEquals(Actor.Tic, out.ui.bubble!!.actor)
        assertEquals(R.string.guide_pair_tap, out.ui.bubble!!.text)
    }

    @Test fun userStepsNeverClick() {
        val userStep = script(tap("system", systemTarget, R.string.guide_wireless_allow, actor = Actor.User))
        val tracker = tracker(userStep)
        val out = tracker.onSnapshot(systemScreen.snapshot(), 0)
        assertEquals(Actor.User, out.ui.bubble!!.actor)
        assertTrue(out.ui.highlight)
        assertEquals(ControlStrip.Hidden, out.ui.strip)
        assertFalse(out.ui.reach)
        assertTrue(tracker.drive(0, 30_000).isEmpty())
    }

    @Test fun aUserStepIsDoneWhenItsTargetGoesAway() {
        val steps = script(
            tap("allow", systemTarget, R.string.guide_wireless_allow, actor = Actor.User),
            tap("devopts", devOptionsTarget, R.string.guide_pair_tap),
        )
        val tracker = tracker(steps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        val empty = screen { }
        assertEquals(R.string.guide_wireless_allow, tracker.onSnapshot(empty, 1000).ui.bubble!!.text)
        assertEquals(R.string.guide_pair_tap, tracker.onSnapshot(empty, 1700).ui.bubble!!.text)
    }

    @Test fun repeatedTapsAreSpacedAndStopAfterTheLastOne() {
        val tap3 = script(tap("build", Target(listOf("build_number"), listOf("Build number")), R.string.guide_build_number_hello, taps = 3))
        val s = Screen()
        s.row("Build number")
        val tracker = tracker(tap3)
        tracker.onSnapshot(s.snapshot(), 0)
        val clicks = tracker.drive(0, 7_000, stepMs = 10)
        assertEquals(listOf(4500L, 4860L, 5220L), clicks.map { it.first })
        assertTrue(clicks.all { it.second is GuideAction.Click })
    }

    @Test fun repeatedTapsStopWhenTheToastSaysDeveloperModeIsOn() {
        val toast = DoneWhen.Toast(listOf("show_dev_on", "show_dev_already"), listOf("You are now a developer!"))
        val steps = script(
            tap("build", Target(listOf("build_number"), listOf("Build number")), R.string.guide_build_number_hello, taps = 7, done = toast),
            tap("system", systemTarget, R.string.guide_tic_opens),
        )
        val s = Screen()
        s.row("Build number")
        val tracker = tracker(steps)
        tracker.onSnapshot(s.snapshot(), 0)
        val before = tracker.drive(0, 4_900, stepMs = 10)
        assertEquals(2, before.size)
        val out = tracker.onToast("You are now a developer!", 4_950)
        assertEquals(R.string.guide_tic_opens, out.ui.bubble!!.text)
        assertTrue(tracker.drive(1_960, 10_000, stepMs = 10).none { it.second is GuideAction.Click })
    }

    @Test fun anUnrelatedToastDoesNotStopTheTaps() {
        val toast = DoneWhen.Toast(listOf("show_dev_on"), emptyList())
        val steps = script(tap("build", Target(listOf("build_number"), listOf("Build number")), R.string.guide_build_number_hello, taps = 3, done = toast))
        val s = Screen()
        s.row("Build number")
        val tracker = tracker(steps)
        tracker.onSnapshot(s.snapshot(), 0)
        tracker.onToast("You are now 4 steps away from being a developer.", 1000)
        assertEquals(3, tracker.drive(0, 7_000, stepMs = 10).size)
    }

    @Test fun aMissingTargetScrollsThenSearchesThenGoesSadAndRecovers() {
        val steps = script(
            GuideStep(
                "pair", Target(listOf("adb_pair_method_code_title"), listOf("Pair device with pairing code")), Actor.Tic,
                R.string.guide_pair_tap, example = GuideExample("Pair device with pairing code", "Pair new devices"),
                expectedPackages = settings,
            ),
        )
        val withoutTarget = Screen().also { it.list(); it.row("Something else") }.snapshot()
        val tracker = tracker(steps)
        val out0 = tracker.onSnapshot(withoutTarget, 0)
        assertEquals(PetMood.Talk, out0.ui.mood)
        val scrolls = tracker.drive(0, 4_500, stepMs = 25)
        assertEquals(4, scrolls.size)
        assertTrue(scrolls.all { it.second is GuideAction.ScrollForward })
        assertEquals(listOf(600L, 1400L, 2200L, 3000L), scrolls.map { it.first })

        assertEquals(PetMood.Search, tracker.onTick(3_500).ui.mood)
        val sad = tracker.onTick(4_000)
        assertEquals(PetMood.Sad, sad.ui.mood)
        assertEquals(R.string.guide_not_found, sad.ui.bubble!!.text)
        assertEquals("Pair device with pairing code", sad.ui.bubble!!.example!!.title)
        assertEquals("Pair new devices", sad.ui.bubble!!.example!!.summary)
        assertNull(sad.ui.target)

        val found = Screen().also { it.row("Pair device with pairing code") }
        val recovered = tracker.onSnapshot(found.snapshot(), 6_000)
        assertNull(recovered.ui.bubble!!.example)
        assertTrue(recovered.ui.mood != PetMood.Sad && recovered.ui.mood != PetMood.Search)
        assertEquals(PetPlacement.NearTarget, recovered.ui.placement)
        assertTrue(tracker.drive(6_000, 9_000).any { it.second is GuideAction.Click })
    }

    @Test fun whileScrollingTicHasTheScreenAndAMovingUserGetsNoMoreScrolls() {
        val steps = script(tap("devopts", devOptionsTarget, R.string.guide_pair_tap))
        val tracker = tracker(steps)
        tracker.onSnapshot(Screen(cls = "A").also { it.list(); it.row("x") }.snapshot(), 0)
        // Searching by scrolling is Tic acting: a touch must reach the watcher and pause it.
        assertEquals(ControlStrip.Controlling, tracker.onTick(100).ui.strip)
        assertEquals(4, tracker.drive(100, 4_500, stepMs = 25).size)
        assertEquals(ControlStrip.Hidden, tracker.onTick(4_600).ui.strip)
        // The user opens another screen looking for it: Tic does not start scrolling under their finger.
        tracker.onSnapshot(Screen(cls = "B").also { it.list(); it.row("y") }.snapshot(), 5_000)
        assertTrue(tracker.drive(5_000, 10_000).isEmpty())
    }

    @Test fun withoutAScrollableContainerItSearchesAndGoesSad() {
        val tracker = tracker(twoSteps)
        val bare = Screen().also { it.row("Something else") }.snapshot()
        tracker.onSnapshot(bare, 0)
        assertTrue(tracker.drive(0, 5_000).isEmpty())
        assertEquals(PetMood.Search, tracker.onTick(1_500).ui.mood)
        assertEquals(PetMood.Sad, tracker.onTick(4_000).ui.mood)
    }

    @Test fun aWindowChangeRestartsTheSearch() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(Screen(cls = "A").also { it.row("x") }.snapshot(), 0)
        assertEquals(PetMood.Search, tracker.onTick(3_000).ui.mood)
        tracker.onSnapshot(Screen(cls = "B").also { it.row("y") }.snapshot(), 3_100)
        assertTrue(tracker.onTick(5_000).ui.mood != PetMood.Sad)
        assertEquals(PetMood.Sad, tracker.onTick(7_200).ui.mood)
    }

    @Test fun anOptionalStepIsSkippedWhenItsTargetNeverShows() {
        val steps = script(
            GuideStep("optional", systemTarget, Actor.Tic, R.string.guide_software_info, expectedPackages = settings, optional = true),
            tap("devopts", devOptionsTarget, R.string.guide_pair_tap),
        )
        val tracker = tracker(steps)
        val bare = Screen().also { it.row("Something else") }.snapshot()
        assertEquals(R.string.guide_software_info, tracker.onSnapshot(bare, 0).ui.bubble!!.text)
        assertEquals(R.string.guide_pair_tap, tracker.onTick(1_000).ui.bubble!!.text)
    }

    @Test fun aSwitchThatIsAlreadyOnIsDoneWithoutAClick() {
        val steps = script(
            GuideStep(
                "overlay", Target(listOf("permit_draw_overlay"), listOf("Allow display over other apps"), role = Role.Switch),
                Actor.Tic, R.string.guide_perm_overlay, done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
            ),
            GuideStep("back", null, Actor.Tic, R.string.guide_back_done, kind = StepKind.Back),
        )
        val tracker = tracker(steps)
        val s = Screen().also { it.row("Allow display over other apps", checked = true) }
        val out = tracker.onSnapshot(s.snapshot(), 0)
        assertEquals(R.string.guide_back_done, out.ui.bubble!!.text)
        val actions = tracker.drive(0, 6_000)
        assertEquals(listOf<GuideAction>(GuideAction.Back), actions.map { it.second })
        assertEquals(4500L, actions.first().first)
    }

    @Test fun aToggleThatDoesNotTurnOnHandsOverToTheUser() {
        val steps = script(
            GuideStep(
                "overlay", Target(listOf("permit_draw_overlay"), listOf("Allow display over other apps"), role = Role.Switch),
                Actor.Tic, R.string.guide_perm_overlay, done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
            ),
        )
        val tracker = tracker(steps)
        val s = Screen().also { it.row("Allow display over other apps", checked = false) }.snapshot()
        tracker.onSnapshot(s, 0)
        assertTrue(tracker.onTick(4500).action is GuideAction.Click)
        val out = tracker.onSnapshot(s, 6500)
        assertEquals(R.string.guide_user_toggles, out.ui.bubble!!.text)
        assertTrue(out.ui.highlight)
    }

    @Test fun aSwitchWhoseStateCannotBeReadIsNeverTapped() {
        val steps = script(
            GuideStep(
                "overlay", Target(listOf("permit_draw_overlay"), listOf("Allow display over other apps"), role = Role.Switch),
                Actor.Tic, R.string.guide_perm_overlay, done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
            ),
        )
        val tracker = tracker(steps)
        // No checkable node in the row: it might already be on.
        val out = tracker.onSnapshot(Screen().also { it.row("Allow display over other apps") }.snapshot(), 0)
        assertEquals(Actor.User, out.ui.bubble!!.actor)
        assertTrue(tracker.drive(0, 10_000).none { it.second is GuideAction.Click })
    }

    @Test fun theLockScreenCredentialIsTheUsersAndTicWaitsForIt() {
        val toast = DoneWhen.Toast(listOf("show_dev_on"), listOf("You are now a developer!"))
        val steps = script(
            tap("build", Target(listOf("build_number"), listOf("Build number")), R.string.guide_build_number_hello, taps = 2, done = toast),
            GuideStep("pin", null, Actor.User, R.string.guide_pin, kind = StepKind.WaitForUser),
            GuideStep("back", null, Actor.Tic, R.string.guide_back_done, kind = StepKind.Back),
        )
        val about = Screen().also { it.row("Build number") }.snapshot()
        val tracker = tracker(steps)
        tracker.onSnapshot(about, 0)
        assertEquals(2, tracker.drive(0, 5_000, stepMs = 10).size)

        val lock = Screen(cls = "com.android.settings.password.ConfirmLockPassword")
        val pin = tracker.onSnapshot(lock.snapshot(), 5_100)
        assertEquals(R.string.guide_pin, pin.ui.bubble!!.text)
        assertEquals(Actor.User, pin.ui.bubble!!.actor)
        assertEquals(ControlStrip.Hidden, pin.ui.strip)
        assertNull(pin.action)
        assertTrue(tracker.drive(5_100, 12_000).isEmpty())

        val back = tracker.onSnapshot(about, 12_000)
        assertEquals(R.string.guide_back_done, back.ui.bubble!!.text)
        assertEquals(listOf<GuideAction>(GuideAction.Back), tracker.drive(12_000, 18_000).map { it.second })
    }

    @Test fun theCredentialStepIsSkippedWhenThereIsNoLockScreen() {
        val toast = DoneWhen.Toast(listOf("show_dev_on"), listOf("You are now a developer!"))
        val steps = script(
            tap("build", Target(listOf("build_number"), listOf("Build number")), R.string.guide_build_number_hello, taps = 7, done = toast),
            GuideStep("pin", null, Actor.User, R.string.guide_pin, kind = StepKind.WaitForUser),
            GuideStep("back", null, Actor.Tic, R.string.guide_back_done, kind = StepKind.Back),
        )
        val about = Screen().also { it.row("Build number") }.snapshot()
        val tracker = tracker(steps)
        tracker.onSnapshot(about, 0)
        assertEquals(R.string.guide_back_done, tracker.onToast("You are now a developer!", 100).ui.bubble!!.text)
    }

    // ---- pairing ----

    private val readOnly = script(
        GuideStep(
            "read-code", null, Actor.Tic, R.string.guide_code_searching,
            expectedPackages = settings, kind = StepKind.ReadPairingCode,
        ),
    )

    private fun pairingDialog(code: String) = Screen().also {
        it.node(text = "Wi-Fi pairing code")
        it.node(text = code)
        it.node(text = "192.168.0.23:41235")
    }.snapshot()

    @Test fun readsTheCodeAndAddressAndPairsOnce() {
        val tracker = tracker(readOnly)
        val out = tracker.onSnapshot(pairingDialog("482 913"), 0)
        assertEquals(GuideAction.Pair("482913", "192.168.0.23", 41235), out.action)
        assertEquals(PetMood.Happy, out.ui.mood)
        assertEquals(R.string.guide_code_found, out.ui.bubble!!.text)
        assertEquals(listOf("482 913"), out.ui.bubble!!.args)
        assertNull(tracker.onSnapshot(pairingDialog("482 913"), 100).action)
        assertTrue(tracker.drive(100, 10_000).isEmpty())
    }

    @Test fun readsACodeWithoutSpaceAndInsideLongerText() {
        val plain = tracker(readOnly).onSnapshot(pairingDialog("482913"), 0)
        assertEquals("482913", (plain.action as GuideAction.Pair).code)
        val s = Screen().also { it.node(text = "Pairing code 123 456 expires soon") }.snapshot()
        assertEquals("123456", (tracker(readOnly).onSnapshot(s, 0).action as GuideAction.Pair).code)
    }

    @Test fun anAddressAloneIsNotACode() {
        val s = Screen().also { it.node(text = "192.168.0.23:41235") }.snapshot()
        assertNull(tracker(readOnly).onSnapshot(s, 0).action)
    }

    @Test fun asksForTheCodeWhenItCannotBeReadWithinThreeSeconds() {
        val tracker = tracker(readOnly)
        val oem = Screen().also { it.node(text = "Wi-Fi pairing code"); it.node(text = "192.168.0.23:41235") }.snapshot()
        val early = tracker.onSnapshot(oem, 0)
        assertFalse(early.ui.bubble!!.codeEntry)
        assertFalse(tracker.onTick(2_999).ui.bubble!!.codeEntry)
        val asking = tracker.onTick(3_000)
        assertTrue(asking.ui.bubble!!.codeEntry)
        assertEquals(R.string.guide_code_entry, asking.ui.bubble!!.text)
        assertEquals(PetPlacement.AboveKeyboard, asking.ui.placement)
        assertEquals(PetMood.Talk, asking.ui.mood)

        assertNull(tracker.onCodeTyped("48291", 3_500).action)
        val typed = tracker.onCodeTyped("482 913", 4_000)
        assertEquals(GuideAction.Pair("482913", "192.168.0.23", 41235), typed.action)
        assertEquals(PetMood.Happy, typed.ui.mood)
        assertNull(tracker.onCodeTyped("482913", 4_100).action)
    }

    // ---- pause, farewell, blocked ----

    @Test fun pauseSleepsWithoutActionsAndResumeRestartsTheStep() {
        val tracker = tracker(twoSteps)
        val snap = systemScreen.snapshot()
        tracker.onSnapshot(snap, 0)
        val paused = tracker.onPause(700)
        assertEquals(ControlStrip.Paused, paused.ui.strip)
        assertEquals(PetPlacement.Sleeping, paused.ui.placement)
        assertEquals(PetMood.Sleep, paused.ui.mood)
        assertNull(paused.ui.bubble)
        assertFalse(paused.ui.reach)
        assertFalse(paused.ui.highlight)
        assertNull(paused.ui.target)
        assertTrue(tracker.drive(700, 8_000).isEmpty())
        assertNull(tracker.onSnapshot(snap, 5_000).action)

        val resumed = tracker.onResume(8_000)
        assertEquals(ControlStrip.Controlling, resumed.ui.strip)
        assertNotNull(resumed.ui.bubble)
        assertNull(tracker.onTick(12_499).action)
        assertTrue(tracker.onTick(12_500).action is GuideAction.Click)
    }

    @Test fun farewellBowsThenFinishesOnce() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        val out = tracker.onAppForeground(500, unattended = false)
        assertTrue(out.ui.farewell)
        assertEquals(R.string.guide_farewell_attended, out.ui.bubble!!.text)
        assertEquals(GuideAction.Finished, out.action)
        val again = tracker.onAppForeground(600, unattended = true)
        assertTrue(again.ui.farewell)
        // The last word is chosen once: a second foreground event does not change it mid-bow.
        assertEquals(R.string.guide_farewell_attended, again.ui.bubble!!.text)
        assertNull(again.action)
        assertTrue(tracker.drive(600, 5_000).isEmpty())
    }

    @Test fun unattendedFarewellSaysTheAppCarriesOn() {
        val tracker = tracker(twoSteps)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        assertEquals(R.string.guide_farewell_unattended, tracker.onAppForeground(500, unattended = true).ui.bubble!!.text)
    }

    @Test fun debuggingDisallowedIsASadBubbleAndNothingElse() {
        val env = GuideEnv(developerOptionsEnabled = false, debuggingDisallowed = true)
        val tracker = tracker(GuideScripts.developerOptionsUnlock(env), env = env)
        val s = Screen().also { it.row("Build number") }.snapshot()
        val out = tracker.onSnapshot(s, 0)
        assertEquals(PetMood.Sad, out.ui.mood)
        assertEquals(R.string.guide_debugging_disallowed, out.ui.bubble!!.text)
        assertFalse(out.ui.highlight)
        assertNull(out.ui.target)
        assertTrue(tracker.drive(0, 20_000).isEmpty())
        assertEquals(PetMood.Sad, tracker.onSnapshot(s, 30_000).ui.mood)
    }

    @Test fun finishingTheLastStepShowsTheDoneBubble() {
        val one = script(tap("system", systemTarget, R.string.guide_tic_opens))
        val tracker = tracker(one)
        tracker.onSnapshot(systemScreen.snapshot(), 0)
        tracker.onTick(4500)
        val out = tracker.onSnapshot(Screen(cls = "Other").also { it.row("x") }.snapshot(), 4_800)
        assertEquals(R.string.guide_done, out.ui.bubble!!.text)
        assertEquals(PetMood.Happy, out.ui.mood)
        assertNull(out.action)
    }

    @Test fun backStepPressesBackOnceAfterTheBubble() {
        val steps = script(
            GuideStep("back", null, Actor.Tic, R.string.guide_back_done, kind = StepKind.Back),
            tap("system", systemTarget, R.string.guide_tic_opens),
        )
        val tracker = tracker(steps)
        val first = tracker.onSnapshot(Screen().also { it.row("x") }.snapshot(), 0)
        assertEquals(ControlStrip.Controlling, first.ui.strip)
        val actions = tracker.drive(0, 6_000)
        assertEquals(listOf<GuideAction>(GuideAction.Back), actions.map { it.second })
        assertEquals(4500L, actions.single().first)
    }

    @Test fun openDeveloperOptionsStepStartsThemOnceAfterTheBubble() {
        val steps = script(
            GuideStep("open", null, Actor.Tic, R.string.guide_unlocked_opening, kind = StepKind.OpenDeveloperOptions),
            tap("system", systemTarget, R.string.guide_tic_opens),
        )
        val tracker = tracker(steps)
        val first = tracker.onSnapshot(Screen().also { it.row("x") }.snapshot(), 0)
        assertEquals(R.string.guide_unlocked_opening, first.ui.bubble!!.text)
        assertEquals(ControlStrip.Controlling, first.ui.strip)
        val actions = tracker.drive(0, 6_000)
        assertEquals(listOf<GuideAction>(GuideAction.OpenDeveloperOptions), actions.map { it.second })
        assertEquals(4500L, actions.single().first)
    }
}
