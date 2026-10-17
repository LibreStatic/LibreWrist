package io.openshm.patcher.device.guide

import io.openshm.patcher.R

/**
 * The pure state machine behind the guide pet. The accessibility service feeds it window
 * snapshots, toasts and ticks with a clock it owns, draws the [GuideUi] of every [Output] and
 * performs its [GuideAction]. Nothing here touches the Android framework.
 *
 * Tic only taps on its own when it is sure (an exact match, no second candidate, the expected
 * window, a visible clickable node). Anything else, a click without visible effect after 2 s, or a
 * step the script gives to the user (destructive or security confirmations) is the user's turn:
 * the tracker never emits a [GuideAction.Click] for those.
 */
class GuideTracker(
    private val script: GuideScript,
    resolver: LabelResolver,
    @Suppress("unused") private val env: GuideEnv,
) {
    private val matcher = TextMatcher(resolver)
    private val steps = script.steps

    private enum class Phase { Observe, Act, Await }

    private var started = false
    private var paused = false
    private var farewell = false
    private var farewellText = R.string.guide_farewell_attended
    private var snap: WindowSnapshot? = null
    private var pressSeq = 0
    private var idx = 0
    private var advanced = false
    private var paired = false
    private var pairedByTyping = false
    private var pairedCode = ""
    private var lastHost: String? = null
    private var lastPort: Int? = null

    // State of the current step; every field is reset by [enter].
    private var enteredAt = 0L
    private var quietUntil = 0L
    private var phase = Phase.Observe
    private var userTurn = false
    private var targetSeen = false
    private var goneSince = -1L
    private var missingSince = -1L
    private var missingSig = ""
    private var canScroll = false
    private var scrolls = 0
    private var lastScrollAt = 0L
    private var unsureSince = -1L
    private var actStart = -1L
    private var tapsDone = 0
    private var nextTapAt = 0L
    private var pressedFirst = false
    private var clickedAt = 0L
    private var clickFingerprint = 0
    private var toastHit = false
    private var lastMatch: Match? = null

    fun onSnapshot(s: WindowSnapshot, nowMs: Long): Output {
        begin(nowMs)
        snap = s
        return run(nowMs)
    }

    fun onToast(text: String, nowMs: Long): Output {
        begin(nowMs)
        val done = steps.getOrNull(idx)?.done as? DoneWhen.Toast
        if (done != null && toastMatches(done, text)) toastHit = true
        return run(nowMs)
    }

    fun onTick(nowMs: Long): Output = run(nowMs)

    /** The user touched the screen while Tic was in control: sleep until [onResume]. */
    fun onPause(nowMs: Long): Output {
        begin(nowMs)
        if (!farewell) paused = true
        return Output(buildUi(nowMs), null)
    }

    /** Tic was tapped while asleep: start the current step again. */
    fun onResume(nowMs: Long): Output {
        begin(nowMs)
        if (paused) {
            paused = false
            enter(nowMs, 0)
        }
        return run(nowMs)
    }

    /** The user typed the pairing code into the bubble's field. */
    fun onCodeTyped(code: String, nowMs: Long): Output {
        begin(nowMs)
        val digits = code.filter { it.isDigit() }
        var action: GuideAction? = null
        if (!paused && !farewell && !paired && digits.length == 6 && steps.getOrNull(idx)?.kind == StepKind.ReadPairingCode) {
            action = pair(digits, typed = true)
        }
        return Output(buildUi(nowMs), action)
    }

    /**
     * LibreWrist is back in the foreground: a last word, bow, smoke, then [GuideAction.Finished]. With
     * [unattended] on the app carries on by itself; otherwise the next step is the user's.
     */
    fun onAppForeground(nowMs: Long, unattended: Boolean): Output {
        begin(nowMs)
        val first = !farewell
        if (first) farewellText = if (unattended) R.string.guide_farewell_unattended else R.string.guide_farewell_attended
        farewell = true
        paused = false
        return Output(buildUi(nowMs), if (first) GuideAction.Finished else null)
    }

    // ---- driver ----

    private fun begin(now: Long) {
        if (started) return
        started = true
        enter(now, 0)
    }

    private fun run(now: Long): Output {
        begin(now)
        val action = if (paused || farewell) null else update(now)
        return Output(buildUi(now), action)
    }

    private fun update(now: Long): GuideAction? {
        repeat(8) {
            advanced = false
            evaluate(now)?.let { return it }
            if (!advanced) return null
        }
        return null
    }

    private fun enter(now: Long, quietMs: Long) {
        enteredAt = now
        quietUntil = now + quietMs
        phase = Phase.Observe
        userTurn = false
        targetSeen = false
        goneSince = -1L
        missingSince = -1L
        missingSig = ""
        canScroll = false
        scrolls = 0
        lastScrollAt = 0L
        unsureSince = -1L
        actStart = -1L
        tapsDone = 0
        nextTapAt = 0L
        pressedFirst = false
        clickedAt = 0L
        toastHit = false
        lastMatch = null
    }

    /** Moves to the next step; [quietMs] holds the target search while the screen is still changing. */
    private fun advance(now: Long, quietMs: Long = 0) {
        idx++
        advanced = true
        enter(now, quietMs)
    }

    private fun evaluate(now: Long): GuideAction? {
        val step = steps.getOrNull(idx) ?: return null
        return when (step.kind) {
            StepKind.Blocked -> null
            StepKind.Back, StepKind.OpenDeveloperOptions -> {
                if (now - enteredAt < READ_MS) return null
                advance(now, SETTLE_MS)
                if (step.kind == StepKind.Back) GuideAction.Back else GuideAction.OpenDeveloperOptions
            }
            StepKind.WaitForUser -> {
                val s = snap
                if (s == null || !isCredential(s)) advance(now)
                null
            }
            StepKind.ReadPairingCode -> readPairingCode(step, now)
            StepKind.Tap, StepKind.Toggle -> tapStep(step, now)
        }
    }

    // ---- tap and toggle steps ----

    private fun isUser(step: GuideStep) = step.actor == Actor.User || userTurn

    private fun tapStep(step: GuideStep, now: Long): GuideAction? {
        val s = snap ?: return null
        val target = step.target ?: return null
        if (toastHit) {
            advance(now)
            return null
        }
        if (isCredential(s)) {
            // The user's PIN is next: jump to its step, whatever Tic was doing.
            val wait = (idx + 1 until steps.size).firstOrNull { steps[it].kind == StepKind.WaitForUser }
            if (wait != null) {
                idx = wait
                advanced = true
                enter(now, 0)
                return null
            }
        }
        val result = matcher.find(s, target)
        val match = result.match
        lastMatch = match
        if (step.kind == StepKind.Toggle && match?.checked == true) {
            advance(now)
            return null
        }
        if (match != null) {
            targetSeen = true
            goneSince = -1L
            missingSince = -1L
        }
        val next = steps.getOrNull(idx + 1)
        val nextTarget = next?.target
        // Past this step already: its target is gone, or only a look-alike remains (a page title repeating the row's label).
        if (nextTarget != null && (match == null || isUser(step) || !isSure(step, s, result)) &&
            (next.kind == StepKind.Tap || next.kind == StepKind.Toggle) &&
            windowOk(next, s) && matcher.find(s, nextTarget).match != null
        ) {
            advance(now)
            return null
        }
        if (phase == Phase.Await) return awaitEffect(step, s, now)
        if (match == null) return missing(step, s, now)
        if (isUser(step)) return null
        val sure = isSure(step, s, result)
        if (!sure && phase == Phase.Act && tapsDone > 0) return null // mid-sequence flicker: keep going
        if (!sure) {
            phase = Phase.Observe
            // A window that is still opening gets a moment; anything else is plain doubt.
            val doubtOnlyWindow = match.exact && !result.ambiguous && match.clickNode.clickable && !windowOk(step, s)
            if (!doubtOnlyWindow) {
                handToUser()
            } else {
                if (unsureSince < 0) unsureSince = now
                if (now - unsureSince >= WINDOW_GRACE_MS) handToUser()
            }
            return null
        }
        unsureSince = -1L
        if (phase == Phase.Observe) {
            phase = Phase.Act
            // The bubble stays up for READ_MS before the tap lands, so the user can read what Tic is doing.
            actStart = maxOf(now, enteredAt + READ_MS - CLICK_MS)
        }
        return act(step, s, match, now)
    }

    private fun act(step: GuideStep, s: WindowSnapshot, match: Match, now: Long): GuideAction? {
        if (tapsDone == 0) {
            if (!pressedFirst && now - actStart >= PRESS_MS) {
                pressedFirst = true
                pressSeq++
            }
            if (now - actStart < CLICK_MS) return null
        } else {
            if (now < nextTapAt) return null
            pressSeq++
        }
        tapsDone++
        if (tapsDone >= step.taps) {
            phase = Phase.Await
            clickedAt = now
            clickFingerprint = fingerprint(s)
        } else {
            nextTapAt = now + REPEAT_MS
        }
        return GuideAction.Click(match.clickNode.id)
    }

    private fun awaitEffect(step: GuideStep, s: WindowSnapshot, now: Long): GuideAction? {
        // A repeated tap shows no change of its own: only a toast, the lock screen or the next target count.
        // A switch only counts once it reads on (checked above), or once the next step's target shows.
        if (step.taps <= 1 && step.kind != StepKind.Toggle && fingerprint(s) != clickFingerprint) {
            advance(now, SETTLE_MS)
            return null
        }
        if (now - clickedAt >= EFFECT_MS) handToUser()
        return null
    }

    private fun handToUser() {
        userTurn = true
        phase = Phase.Observe
        unsureSince = -1L
    }

    private fun missing(step: GuideStep, s: WindowSnapshot, now: Long): GuideAction? {
        if (targetSeen && isUser(step)) {
            // The thing the user had to tap went away: they did it.
            if (goneSince < 0) goneSince = now
            if (now - goneSince >= GONE_MS) advance(now, SETTLE_MS)
            return null
        }
        targetSeen = false
        if (phase == Phase.Act && tapsDone > 0) {
            advance(now, SETTLE_MS)
            return null
        }
        phase = Phase.Observe
        val sig = "${s.packageName}/${s.className}"
        if (missingSince < 0) {
            if (now < quietUntil) return null
            missingSince = now
            missingSig = sig
            lastScrollAt = now - SCROLL_FIRST_DELAY_GAP
        } else if (sig != missingSig) {
            // The scroll budget is per step: when the user moves around looking for it, Tic does not scroll under them.
            missingSig = sig
            missingSince = now
            lastScrollAt = now - SCROLL_FIRST_DELAY_GAP
        }
        if (step.optional && now - missingSince >= OPTIONAL_MS) {
            advance(now)
            return null
        }
        val scroller = s.nodes.filter { it.scrollable && it.visibleToUser }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        canScroll = scroller != null
        if (scroller != null && scrolls < MAX_SCROLLS && now - lastScrollAt >= SCROLL_EVERY_MS) {
            scrolls++
            lastScrollAt = now
            return GuideAction.ScrollForward(scroller.id)
        }
        return null
    }

    private fun isSure(step: GuideStep, s: WindowSnapshot, result: MatchResult): Boolean {
        val match = result.match ?: return false
        // A switch whose state cannot be read might already be on: tapping it could turn a grant off.
        if (step.kind == StepKind.Toggle && match.checked == null) return false
        return match.exact && !result.ambiguous && match.node.visibleToUser &&
            match.clickNode.clickable && windowOk(step, s)
    }

    private fun windowOk(step: GuideStep, s: WindowSnapshot): Boolean =
        (step.expectedPackages.isEmpty() || s.packageName in step.expectedPackages) &&
            (step.expectedClassHint == null || s.className.orEmpty().contains(step.expectedClassHint))

    // ---- pairing ----

    private fun readPairingCode(step: GuideStep, now: Long): GuideAction? {
        if (paired) return null
        // Right after the tap the Wireless debugging page (with its own numbers) can still be the snapshot.
        if (now < quietUntil) return null
        val s = snap ?: return null
        if (step.expectedPackages.isNotEmpty() && s.packageName !in step.expectedPackages) return null
        val texts = s.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }
        texts.firstNotNullOfOrNull { hostPort.find(it) }?.let {
            lastHost = it.groupValues[1]
            lastPort = it.groupValues[2].toIntOrNull()
        }
        val code = texts.firstNotNullOfOrNull { codeOnly.find(it) }
            ?: texts.firstNotNullOfOrNull { codeInText.find(it) }
        if (code == null) return null
        return pair(code.groupValues[1] + code.groupValues[2], typed = false)
    }

    private fun pair(code: String, typed: Boolean): GuideAction {
        paired = true
        pairedByTyping = typed
        pairedCode = "${code.substring(0, 3)} ${code.substring(3)}"
        return GuideAction.Pair(code, lastHost, lastPort)
    }

    private fun isCredential(s: WindowSnapshot): Boolean {
        val cls = s.className.orEmpty()
        if (credentialClasses.any { cls.contains(it) }) return true
        return s.packageName == GuideScripts.SETTINGS &&
            s.nodes.any { it.viewId?.contains("password_entry") == true }
    }

    private fun toastMatches(done: DoneWhen.Toast, text: String): Boolean {
        val shown = TextMatcher.normalize(text)
        if (shown.isEmpty()) return false
        val labels = done.settingsRes.mapNotNull { matcher.settings(it) } + done.fallbacks
        return labels.map(TextMatcher::normalize).filter { it.isNotEmpty() }.any { shown.contains(it) }
    }

    private fun fingerprint(s: WindowSnapshot): Int {
        var h = "${s.packageName}/${s.className}".hashCode()
        for (n in s.nodes) {
            if (!n.visibleToUser) continue
            h = 31 * h + (n.text?.hashCode() ?: 0)
            h = 31 * h + (n.contentDescription?.hashCode() ?: 0)
            h = 31 * h + n.checked.hashCode()
        }
        return h
    }

    // ---- what the overlay draws ----

    private fun buildUi(now: Long): GuideUi {
        if (!started) return GuideUi()
        if (farewell) {
            return GuideUi(visible = true, mood = PetMood.Happy, farewell = true, bubble = GuideBubble(farewellText), pressSeq = pressSeq)
        }
        if (paused) {
            return GuideUi(
                visible = true, mood = PetMood.Sleep, placement = PetPlacement.Sleeping,
                strip = ControlStrip.Paused, pressSeq = pressSeq,
            )
        }
        val step = steps.getOrNull(idx)
            ?: return GuideUi(
                visible = true, mood = PetMood.Happy, bubble = GuideBubble(R.string.guide_done), pressSeq = pressSeq,
            )
        val settled = now - enteredAt >= BUBBLE_MS
        val point = if (settled) PetMood.Point else PetMood.Talk
        return when (step.kind) {
            StepKind.Blocked -> GuideUi(
                visible = true, mood = PetMood.Sad, bubble = GuideBubble(step.bubble), pressSeq = pressSeq,
            )
            StepKind.Back, StepKind.OpenDeveloperOptions -> GuideUi(
                visible = true, mood = point, bubble = GuideBubble(step.bubble, actor = Actor.Tic),
                strip = ControlStrip.Controlling, pressSeq = pressSeq,
            )
            StepKind.WaitForUser -> GuideUi(
                visible = true, mood = PetMood.Talk, bubble = GuideBubble(step.bubble, actor = Actor.User),
                pressSeq = pressSeq,
            )
            StepKind.ReadPairingCode -> readingUi(step, now, settled)
            StepKind.Tap, StepKind.Toggle -> tapUi(step, now, point)
        }
    }

    private fun readingUi(step: GuideStep, now: Long, settled: Boolean): GuideUi = when {
        paired -> GuideUi(
            visible = true, mood = PetMood.Happy, pressSeq = pressSeq,
            bubble = if (pairedByTyping) GuideBubble(R.string.guide_code_thanks)
            else GuideBubble(R.string.guide_code_found, listOf(pairedCode)),
        )
        now - enteredAt >= CODE_READ_MS -> GuideUi(
            visible = true, mood = PetMood.Talk, placement = PetPlacement.AboveKeyboard, pressSeq = pressSeq,
            bubble = GuideBubble(R.string.guide_code_entry, codeEntry = true),
        )
        else -> GuideUi(
            visible = true, mood = if (settled) PetMood.Search else PetMood.Talk, pressSeq = pressSeq,
            bubble = GuideBubble(step.bubble),
        )
    }

    private fun tapUi(step: GuideStep, now: Long, point: PetMood): GuideUi {
        val target = step.target
        val match = lastMatch
        val user = isUser(step)
        val actor = if (user) Actor.User else Actor.Tic
        val label = match?.node?.text?.takeIf { it.isNotBlank() } ?: target?.let { matcher.label(it) }.orEmpty()
        val lost = match == null && missingSince >= 0 && !(user && targetSeen)
        if (lost) {
            val missingFor = now - missingSince
            val exhausted = !canScroll || scrolls >= MAX_SCROLLS
            return when {
                missingFor >= SAD_MS && exhausted -> {
                    val title = target?.let { matcher.settingsLabel(it) } ?: step.example?.title ?: label
                    GuideUi(
                        visible = true, mood = PetMood.Sad, pressSeq = pressSeq,
                        // Tic asks the user to look for it: their turn, whoever the step belonged to.
                        bubble = GuideBubble(
                            R.string.guide_not_found, listOf(label), Actor.User,
                            GuideExample(title, step.example?.summary),
                        ),
                    )
                }
                missingFor >= SEARCH_MS && exhausted -> GuideUi(
                    visible = true, mood = PetMood.Search, pressSeq = pressSeq,
                    bubble = GuideBubble(R.string.guide_searching, listOf(label), actor),
                )
                // Still scrolling for it: Tic has the screen, so a touch puts it to sleep.
                else -> GuideUi(
                    visible = true, mood = point, pressSeq = pressSeq,
                    bubble = GuideBubble(step.bubble, listOf(label), actor),
                    strip = if (exhausted) ControlStrip.Hidden else ControlStrip.Controlling,
                )
            }
        }
        val bubbleRes = when {
            step.actor == Actor.Tic && userTurn ->
                if (step.kind == StepKind.Toggle) R.string.guide_user_toggles else R.string.guide_user_taps
            else -> step.bubble
        }
        val bubble = GuideBubble(bubbleRes, listOf(label), actor)
        if (user) {
            return GuideUi(
                visible = true, mood = point, bubble = bubble, target = match?.bounds,
                highlight = match != null, pressSeq = pressSeq,
            )
        }
        val acting = phase != Phase.Observe && match != null
        return GuideUi(
            visible = true, mood = point, bubble = bubble, target = match?.bounds,
            reach = acting && (phase == Phase.Await || now - actStart >= REACH_MS),
            pressSeq = pressSeq,
            placement = if (acting) PetPlacement.NearTarget else PetPlacement.Corner,
            strip = if (acting) ControlStrip.Controlling else ControlStrip.Hidden,
        )
    }

    private companion object {
        // Timings follow the approved mock (bubble, then reach, press, tap; repeated taps 360 ms apart).
        const val BUBBLE_MS = 1500L
        /** Shortest time a step's bubble shows before Tic acts on it. */
        const val READ_MS = 4500L
        const val REACH_MS = 600L
        const val PRESS_MS = 1150L
        const val CLICK_MS = 1500L
        const val REPEAT_MS = 360L
        const val EFFECT_MS = 2000L
        const val SETTLE_MS = 700L
        const val WINDOW_GRACE_MS = 700L
        const val GONE_MS = 600L
        const val OPTIONAL_MS = 1000L
        const val MAX_SCROLLS = 4
        const val SCROLL_EVERY_MS = 800L
        const val SCROLL_FIRST_DELAY_GAP = 200L
        const val SEARCH_MS = 1500L
        const val SAD_MS = 4000L
        const val CODE_READ_MS = 3000L

        val credentialClasses = listOf("ConfirmLock", "ConfirmDeviceCredential", "ConfirmCredential")
        val codeOnly = Regex("""^\s*(\d{3})[\s  ]?(\d{3})\s*$""")
        val codeInText = Regex("""(?<![\d.:])(\d{3})[   ]?(\d{3})(?![\d]|[.:]\d)""")
        val hostPort = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")
    }
}
