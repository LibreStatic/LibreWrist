package io.openshm.patcher.device.guide

/**
 * What the pure guide engine consumes and produces. Nothing here touches the Android framework:
 * the accessibility service flattens an AccessibilityNodeInfo tree into [WindowSnapshot] and
 * executes the [GuideAction]s the [GuideTracker] returns.
 */
data class GuideNode(
    /** Unique within one [WindowSnapshot]; the service maps it back to a live node to act on. */
    val id: Int,
    val text: String?,
    val contentDescription: String?,
    /** Fully qualified resource id (`com.android.settings:id/title`) when the app reports one. */
    val viewId: String?,
    val className: String?,
    val bounds: GuideRect,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    /** Can still scroll forward (scrollable and not at its end). */
    val scrollable: Boolean,
    val visibleToUser: Boolean,
    val parentId: Int?,
)

data class WindowSnapshot(
    val packageName: String,
    val className: String?,
    val nodes: List<GuideNode>,
    val screenWidth: Int,
    val screenHeight: Int,
)

/** Resolves a string of the Settings app by resource name, in the user's language and OEM skin. */
fun interface LabelResolver {
    fun settingsString(name: String): String?
}

/** Facts about the phone that the app knows before the guide starts. */
data class GuideEnv(
    val developerOptionsEnabled: Boolean,
    /** `UserManager.DISALLOW_DEBUGGING_FEATURES` is set: no script that needs debugging can work. */
    val debuggingDisallowed: Boolean,
)

/** What the service must do on the screen. At most one per call. */
sealed interface GuideAction {
    data class Click(val nodeId: Int) : GuideAction
    data class ScrollForward(val nodeId: Int) : GuideAction
    data object Back : GuideAction

    /** Start the Developer options screen (they were just unlocked). */
    data object OpenDeveloperOptions : GuideAction

    /** Pair with this code; [host] and [port] are null when they could not be read. */
    data class Pair(val code: String, val host: String?, val port: Int?) : GuideAction

    /** The guide is over (after the farewell); the service removes the overlay. */
    data object Finished : GuideAction
}

data class Output(val ui: GuideUi, val action: GuideAction?)
