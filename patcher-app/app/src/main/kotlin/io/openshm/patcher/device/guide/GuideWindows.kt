package io.openshm.patcher.device.guide

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.openshm.patcher.ui.guide.GuideOverlay
import io.openshm.patcher.ui.theme.PatcherTheme
import kotlinx.coroutines.flow.StateFlow

/**
 * The guide's windows, all `TYPE_ACCESSIBILITY_OVERLAY` (no overlay permission needed):
 * - the drawing: the whole screen above the navigation bar, never takes a touch, except while the code boxes are shown, when it also
 *   takes focus so the keyboard can type into them;
 * - the watcher: one touchable pixel with `FLAG_WATCH_OUTSIDE_TOUCH`, so every touch of the user anywhere
 *   else arrives as `ACTION_OUTSIDE` and pauses Tic (the service's own clicks are not touches);
 * - the wake spot: a touchable patch over the sleeping Tic, only while paused.
 * Must be used from the main thread.
 */
internal class GuideWindows(
    private val context: Context,
    private val ui: StateFlow<GuideUi>,
    /** A touch outside Tic's windows; the flag is true when it carries no tool type (the echo of Tic's own tap). */
    private val onUserTouch: (Boolean) -> Unit,
    private val onPetTap: () -> Unit,
    private val onCodeEntered: (String) -> Unit,
    private val onCodeDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private var drawing: ComposeView? = null
    private var watcher: View? = null
    private var wakeSpot: View? = null
    private var codeEntry = false
    private var area = Rect()
    private var screenOffset by mutableStateOf(IntOffset.Zero)
    private var topInset by mutableIntStateOf(0)

    val shown: Boolean get() = drawing != null

    fun show() {
        if (drawing != null) return
        owner.start()
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val state by ui.collectAsStateWithLifecycle()
                PatcherTheme {
                    GuideOverlay(state, onPetTap, onCodeEntered, screenOffset = screenOffset, onCodeDismiss = onCodeDismiss, topInsetPx = topInset)
                }
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val at = IntArray(2).also(v::getLocationOnScreen)
                screenOffset = IntOffset(at[0], at[1])
            }
        }
        area = usableArea()
        topInset = statusBarHeight()
        windowManager.addView(view, drawingParams(focusable = false))
        drawing = view
        addWatcher()
    }

    /** Applies the parts of [GuideUi] that change the windows themselves rather than what they draw. */
    fun sync(state: GuideUi) {
        if (drawing == null) return
        val wantsCode = state.bubble?.codeEntry == true
        val nowArea = usableArea()
        topInset = statusBarHeight()
        if (wantsCode != codeEntry || nowArea != area) {
            codeEntry = wantsCode
            area = nowArea
            drawing?.let { windowManager.updateViewLayout(it, drawingParams(focusable = wantsCode)) }
        }
        // While Tic is asleep the user's touches are expected, and while typing the code they are on our window.
        val watching = state.strip == ControlStrip.Controlling && !wantsCode
        if (watching && watcher == null) addWatcher() else if (!watching) removeView(watcher).also { watcher = null }
        val paused = state.strip == ControlStrip.Paused
        if (paused && wakeSpot == null) addWakeSpot() else if (!paused) removeView(wakeSpot).also { wakeSpot = null }
    }

    fun hide() {
        removeView(wakeSpot)
        removeView(watcher)
        removeView(drawing)
        wakeSpot = null
        watcher = null
        drawing = null
        codeEntry = false
        owner.stop()
    }

    private fun drawingParams(focusable: Boolean) = LayoutParams(
        area.width(),
        area.height(),
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (focusable) LayoutParams.FLAG_NOT_TOUCH_MODAL else LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        gravity = Gravity.TOP or Gravity.START
        x = area.left
        y = area.top
        softInputMode = if (focusable) LayoutParams.SOFT_INPUT_STATE_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_NOTHING else 0
        title = "LibreWrist guide"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addWatcher() {
        if (watcher != null) return
        val view = View(context).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onUserTouch(event.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN)
                }
                false
            }
        }
        val params = LayoutParams(
            1, 1,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
        watcher = view
    }

    private fun addWakeSpot() {
        val density = context.resources.displayMetrics.density
        val view = View(context).apply {
            contentDescription = context.getString(io.openshm.patcher.R.string.guide_wake_description)
            setOnClickListener { onPetTap() }
        }
        val params = LayoutParams(
            (WAKE_WIDTH_DP * density).toInt(),
            (WAKE_HEIGHT_DP * density).toInt(),
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            val screen = windowManager.maximumWindowMetrics.bounds
            x = area.left - screen.left
            y = screen.bottom - area.bottom
        }
        windowManager.addView(view, params)
        wakeSpot = view
    }

    /**
     * The screen without the navigation bar. Over the bar, even a window that takes no touch can keep the
     * gesture pill (a launcher-drawn taskbar on large screens) from working. Accessibility overlays ignore
     * `fitInsetsTypes`, so the windows are sized from the insets instead.
     */
    private fun usableArea(): Rect {
        val metrics = windowManager.maximumWindowMetrics
        val nav = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        val b = metrics.bounds
        return Rect(b.left + nav.left, b.top, b.right - nav.right, b.bottom - nav.bottom)
    }

    /** The status bar, or the camera cutout when it reaches lower: the strip and Tic stay below both. */
    private fun statusBarHeight(): Int = windowManager.maximumWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top

    private fun removeView(view: View?) {
        // Not isAttachedToWindow: a view added in this same frame is not attached yet and would stay on screen.
        if (view != null) runCatching { windowManager.removeViewImmediate(view) }
    }

    private companion object {
        // Covers the sleeping Tic (84 x 109 dp at the bottom-left corner) and the strip under it, with room for a thumb.
        const val WAKE_WIDTH_DP = 110f
        const val WAKE_HEIGHT_DP = 170f
    }
}

/** The lifecycle a ComposeView needs when it lives in a service window instead of an activity. */
private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performRestore(null)
    }

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun stop() {
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
    }
}
