package io.openshm.patcher.device.guide

import androidx.annotation.StringRes

/**
 * What the guide overlay draws at one moment. Produced by the pure [GuideTracker], drawn by
 * `ui/guide/GuideOverlay`. Coordinates are screen pixels as reported by
 * AccessibilityNodeInfo.getBoundsInScreen; the overlay converts them to its own space.
 */
data class GuideUi(
    val visible: Boolean = false,
    val mood: PetMood = PetMood.Idle,
    val bubble: GuideBubble? = null,
    /** The node the current step is about, or null when there is none on screen. */
    val target: GuideRect? = null,
    /** Blinking box around [target]: only on the user's own steps ("your turn"). */
    val highlight: Boolean = false,
    /** Tic stretches its own arm to a glove resting on [target]: only while Tic taps itself. */
    val reach: Boolean = false,
    /** Increments once per press of the glove; the overlay plays one press animation per change. */
    val pressSeq: Int = 0,
    val placement: PetPlacement = PetPlacement.Corner,
    val strip: ControlStrip = ControlStrip.Hidden,
    /** Bow, then a puff of smoke, then the overlay goes away. */
    val farewell: Boolean = false,
)

data class GuideRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

enum class PetMood { Idle, Talk, Point, Happy, Sad, Search, Sleep }

/** Who does the current step. Drawn as the chip at the top of the bubble. */
enum class Actor {
    /** "I'll do it" chip: Tic taps on its own. */
    Tic,
    /** "Your turn" chip: the user taps, Tic only points. */
    User,
}

data class GuideBubble(
    @StringRes val text: Int,
    val args: List<String> = emptyList(),
    /** Null for bubbles that are not about a tap (greetings, reading, done). */
    val actor: Actor? = null,
    /** Shown under the text when Tic can't find the target: a mini Settings row to look for. */
    val example: GuideExample? = null,
    /** The bubble holds a six-digit code field and the overlay asks for the keyboard. */
    val codeEntry: Boolean = false,
)

data class GuideExample(val title: String, val summary: String? = null)

enum class PetPlacement {
    /** A screen corner that keeps [GuideUi.target] and the bubble clear of each other. */
    Corner,
    /** Next to the target, below it (above when there is no room): used while Tic taps. */
    NearTarget,
    /** Standing on top of the keyboard while the code field is shown. */
    AboveKeyboard,
    /** Bottom-left corner, asleep, while paused. */
    Sleeping,
}

enum class ControlStrip { Hidden, Controlling, Paused }
