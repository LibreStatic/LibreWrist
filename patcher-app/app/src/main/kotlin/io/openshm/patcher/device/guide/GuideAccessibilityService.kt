package io.openshm.patcher.device.guide

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import io.openshm.patcher.R
import io.openshm.patcher.data.Automation
import io.openshm.patcher.device.SelfAdbController
import io.openshm.patcher.device.SettingsTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tic, the interactive guide. Inert until the app starts a [GuideSession] right before a Settings
 * hand-off; then it feeds [GuideTracker] what the screen shows, draws its [GuideUi] through
 * [GuideWindows] and performs the tracker's actions. Clicks are only ever performed on Settings and
 * Google Play services windows (the tracker already keeps destructive and security steps for the
 * user; this is the second fence). Everything runs on the main thread.
 */
class GuideAccessibilityService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var windows: GuideWindows? = null
    private val ui = MutableStateFlow(GuideUi())

    private var tracker: GuideTracker? = null
    private var loop: Job? = null
    private var ending: Job? = null
    private var greeting: Job? = null
    /** Since when another app (the home screen, say) has been in front, or -1. */
    private var awaySince = -1L
    private var startedAt = 0L
    /** The hand-off reached another app's window; only after that does our own app coming back end the guide. */
    private var leftApp = false
    /** Live nodes of the last snapshot, by [GuideNode.id], to act on. */
    private var nodes: Map<Int, AccessibilityNodeInfo> = emptyMap()
    private var snapshotDue = true
    /** The activity or dialog class of the last window change, which the tracker checks before tapping. */
    private var lastWindowClass: String? = null
    /** When Tic last acted: Android reports its own clicks to the touch watcher as outside touches. */
    private var ownActionAt = 0L

    override fun onServiceConnected() {
        val main = MainScope().also { scope = it }
        windows = GuideWindows(this, ui, ::onUserTouch, ::onPetTap, ::onCodeEntered, ::onCodeDismiss)
        main.launch {
            GuideSession.active.collect { started ->
                if (started != null && Automation.state.value.guidePet) begin(started.request) else end()
            }
        }
        // Queued after the collector, so its first (empty) value cannot take the greeting down.
        if (GuideSession.greetOnConnect) {
            GuideSession.greetOnConnect = false
            main.launch { greet() }
        }
    }

    /** The user just turned the service on: Tic takes them back to LibreWrist and says so. */
    private fun greet() {
        GuideSession.greetedAt = SystemClock.uptimeMillis()
        ui.value = GuideUi(visible = true, mood = PetMood.Happy, bubble = GuideBubble(R.string.guide_enabled_hello))
        windows?.show()
        windows?.sync(ui.value)
        returnToApp()
        // The app starts the guided screen after the greeting; without one, Tic leaves on its own.
        greeting = scope?.launch {
            delay(GuideSession.GREETING_MS + GREETING_GRACE_MS)
            if (tracker == null) end()
        }
    }

    private fun returnToApp() {
        val task = getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()
        if (task != null && runCatching { task.moveToFront() }.isSuccess) return
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val tracker = tracker ?: return
        val now = SystemClock.uptimeMillis()
        when {
            event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (event.className?.toString()?.startsWith(Toast::class.java.name) == true) {
                    val text = event.text.joinToString(" ").trim()
                    if (text.isNotEmpty()) apply(tracker.onToast(text, now))
                }
            }
            event.packageName?.toString() == packageName -> {
                // Our own windows report here too; only our app taking the screen back counts.
                if (leftApp && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    rootInActiveWindow?.packageName?.toString() == packageName
                ) {
                    apply(tracker.onAppForeground(now, Automation.state.value.unattended))
                }
            }
            else -> {
                leftApp = true
                snapshotDue = true
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastWindowClass = event.className?.toString()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        end()
        // A rebound service must not replay this walk-through from its first step on whatever screen is open.
        GuideSession.finish()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun begin(request: GuideRequest) {
        end()
        val env = GuideEnv(developerOptionsShown(this), debuggingBlocked(this))
        val appLabel = (request as? GuideRequest.Permission)?.pkg?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }
        tracker = GuideTracker(scriptFor(request, env, appLabel), SettingsLabels(this), env)
        startedAt = SystemClock.uptimeMillis()
        leftApp = false
        snapshotDue = true
        lastWindowClass = null
        windows?.show()
        loop = scope?.launch {
            var lastSnapshot = 0L
            while (isActive) {
                val now = SystemClock.uptimeMillis()
                val t = tracker ?: break
                if (now - startedAt > MAX_SESSION_MS) {
                    GuideSession.finish()
                    break
                }
                // Tic only lives on the screens it guides: elsewhere its windows go away, and after a while so does it.
                if (awayFromGuide()) {
                    if (awaySince < 0) awaySince = now
                    windows?.hide()
                    if (now - awaySince > AWAY_END_MS) {
                        GuideSession.finish()
                        break
                    }
                    delay(TICK_MS)
                    continue
                }
                if (awaySince >= 0) {
                    awaySince = -1L
                    windows?.show()
                    windows?.sync(ui.value)
                    snapshotDue = true
                }
                // Content events are coalesced and not every change raises one: look again now and then anyway.
                val snapshot = if (snapshotDue || now - lastSnapshot >= RESNAPSHOT_MS) snapshot() else null
                if (snapshot != null) {
                    snapshotDue = false
                    lastSnapshot = now
                    apply(t.onSnapshot(snapshot, now))
                } else {
                    apply(t.onTick(now))
                }
                delay(TICK_MS)
            }
        }
    }

    private fun end() {
        loop?.cancel()
        ending?.cancel()
        greeting?.cancel()
        loop = null
        ending = null
        greeting = null
        awaySince = -1L
        tracker = null
        nodes = emptyMap()
        windows?.hide()
        ui.value = GuideUi()
    }

    private fun apply(output: Output) {
        if (tracker == null) return
        ui.value = output.ui
        windows?.sync(output.ui)
        val action = output.action
        if (action is GuideAction.Click || action is GuideAction.ScrollForward || action == GuideAction.Back ||
            action == GuideAction.OpenDeveloperOptions
        ) {
            ownActionAt = SystemClock.uptimeMillis()
        }
        when (action) {
            null -> Unit
            is GuideAction.Click -> clickable(action.nodeId)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            is GuideAction.ScrollForward -> clickable(action.nodeId)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            // Global actions have no node to fence: only while Settings (or Play services) is in front.
            GuideAction.Back -> if (inTouchablePackage()) performGlobalAction(GLOBAL_ACTION_BACK)
            // A service the system has bound may start activities from the background.
            GuideAction.OpenDeveloperOptions -> if (inTouchablePackage()) runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
            is GuideAction.Pair -> {
                GuideSession.holdReturnUntil = SystemClock.uptimeMillis() + GuideSession.CODE_READ_MS
                SelfAdbController.submitCode(action.code)
            }
            GuideAction.Finished -> if (ending == null) {
                ending = scope?.launch {
                    delay(FAREWELL_MS)
                    GuideSession.finish()
                }
            }
        }
    }

    /** Another app is in front: not one of the screens the service watches, nor LibreWrist itself. */
    private fun awayFromGuide(): Boolean {
        val front = rootInActiveWindow?.packageName?.toString() ?: return false
        return front != packageName && serviceInfo?.packageNames?.contains(front) == false
    }

    /** The node to act on, only when it lives in a window Tic may touch at all. */
    private fun inTouchablePackage() = rootInActiveWindow?.packageName?.toString() in TOUCHABLE_PACKAGES

    private fun clickable(id: Int): AccessibilityNodeInfo? =
        nodes[id]?.takeIf { it.packageName?.toString() in TOUCHABLE_PACKAGES && it.isVisibleToUser }

    private fun snapshot(): WindowSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == packageName) return null
        // The service may connect with Settings already open and still: no event says so, the snapshot does.
        leftApp = true
        val flat = ArrayList<GuideNode>()
        val live = HashMap<Int, AccessibilityNodeInfo>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>().apply { add(root to null) }
        val bounds = Rect()
        while (queue.isNotEmpty() && flat.size < MAX_NODES) {
            val (node, parent) = queue.removeFirst()
            val id = flat.size
            node.getBoundsInScreen(bounds)
            flat += GuideNode(
                id = id,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                viewId = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = GuideRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                clickable = node.isClickable,
                checkable = node.isCheckable,
                checked = node.isChecked,
                // Only what can still move forward: Settings wraps its list in a ScrollView that is scrollable but stuck.
                scrollable = node.isScrollable && AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD in node.actionList,
                visibleToUser = node.isVisibleToUser,
                parentId = parent,
            )
            live[id] = node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to id) }
        }
        nodes = live
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return WindowSnapshot(pkg, lastWindowClass, flat, metrics.width(), metrics.height())
    }

    private fun onUserTouch(noTool: Boolean) {
        // Tic's own tap comes back as a touch without a tool type; a finger always pauses, even right after it.
        if (noTool && SystemClock.uptimeMillis() - ownActionAt < OWN_ACTION_ECHO_MS) return
        tracker?.let { apply(it.onPause(SystemClock.uptimeMillis())) }
    }

    /** "Not now" (or Back) on the code entry: Tic sleeps, which gives the screen back; tapping Tic asks again. */
    private fun onCodeDismiss() {
        tracker?.let { apply(it.onPause(SystemClock.uptimeMillis())) }
    }

    private fun onPetTap() {
        snapshotDue = true
        tracker?.let { apply(it.onResume(SystemClock.uptimeMillis())) }
    }

    private fun onCodeEntered(code: String) {
        tracker?.let { apply(it.onCodeTyped(code, SystemClock.uptimeMillis())) }
    }

    companion object {
        private const val TICK_MS = 100L
        private const val RESNAPSHOT_MS = 500L
        private const val FAREWELL_MS = 7_500L
        private const val MAX_SESSION_MS = 15 * 60_000L
        private const val AWAY_END_MS = 60_000L
        private const val GREETING_GRACE_MS = 2_000L
        private const val MAX_NODES = 1_500
        private const val OWN_ACTION_ECHO_MS = 1_000L
        private const val SETTINGS = "com.android.settings"
        private val TOUCHABLE_PACKAGES = setOf(SETTINGS, "com.google.android.gms")

        /** The user turned the service on in the accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val info = it.resolveInfo.serviceInfo
                info.packageName == context.packageName && info.name == GuideAccessibilityService::class.java.name
            }
        }

        internal fun scriptFor(request: GuideRequest, env: GuideEnv, appLabel: String? = null): GuideScript = when (request) {
            GuideRequest.WirelessDebugging -> GuideScripts.wirelessDebugging(env)
            GuideRequest.UnknownSources -> GuideScripts.permission(PermissionKind.UnknownSources)
            GuideRequest.PlayServicesClearData -> GuideScripts.playServicesClearData()
            is GuideRequest.Permission -> GuideScripts.permission(
                when (request.target) {
                    SettingsTarget.AppDetails -> PermissionKind.AppDetails
                    SettingsTarget.Overlay -> PermissionKind.Overlay
                    SettingsTarget.ListenerDetail -> PermissionKind.ListenerDetail
                    SettingsTarget.Battery -> PermissionKind.Battery
                },
                appLabel = appLabel,
            )
        }
    }
}

/** Settings' own strings, by resource name, in the user's language and skin. */
private class SettingsLabels(context: Context) : LabelResolver {
    private val resources = try {
        context.packageManager.getResourcesForApplication("com.android.settings")
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
    private val cache = HashMap<String, String?>()

    override fun settingsString(name: String): String? = cache.getOrPut(name) {
        val res = resources ?: return@getOrPut null
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "string", "com.android.settings")
        if (id == 0) null else runCatching { res.getString(id) }.getOrNull()
    }
}
