package io.openshm.patcher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Range and default of [AutomationState.advanceSeconds]. */
const val ADVANCE_SECONDS_MIN = 2
const val ADVANCE_SECONDS_MAX = 60
const val ADVANCE_SECONDS_DEFAULT = 5

/**
 * The automation switches of the Welcome screen. [freeRun] and [uninstallMismatch] only act while
 * [unattended] is on: with it off they stay stored but behave as off (see [freeRunActive] and
 * [uninstallActive]).
 */
data class AutomationState(
    /** Tic, the guide pet, walks the user through the Android Settings screens the app sends them to. */
    val guidePet: Boolean = true,
    /** Do everything that can be automated over ADB and advance between steps on a countdown. */
    val unattended: Boolean = true,
    /** Do not stop at operation confirmations (skipped steps, profile correction, notices). */
    val freeRun: Boolean = false,
    /** Uninstall (with its data) an app signed with another key, without asking. */
    val uninstallMismatch: Boolean = false,
    /** The user ticked "Don't show again" on the free-run warning. */
    val freeRunWarningHidden: Boolean = false,
    /** The first-launch notice (the official apps are always fetched by hand) was acknowledged. */
    val appsNoticeSeen: Boolean = false,
    /** Seconds a countdown takes before a step continues by itself, when Advance freely is off. */
    val advanceSeconds: Int = ADVANCE_SECONDS_DEFAULT,
) {
    val freeRunActive: Boolean get() = unattended && freeRun
    val uninstallActive: Boolean get() = unattended && uninstallMismatch

    /**
     * The apps notice is still to be shown; it only concerns unattended mode (on by default, so on a
     * first launch). Welcome also requires an official app to be missing before showing it.
     */
    val appsNoticePending: Boolean get() = unattended && !appsNoticeSeen
}

/**
 * Process-wide automation settings, persisted in SharedPreferences once [init] has run (the app does
 * it in `PatcherApplication`); in-memory defaults before that, which is what previews and unit tests see.
 */
object Automation {
    private const val PREFS = "automation"
    private const val KEY_GUIDE_PET = "guidePet"
    private const val KEY_UNATTENDED = "unattended"
    private const val KEY_FREE_RUN = "freeRun"
    private const val KEY_UNINSTALL_MISMATCH = "uninstallMismatch"
    private const val KEY_WARNING_HIDDEN = "freeRunWarningHidden"
    private const val KEY_APPS_NOTICE_SEEN = "appsNoticeSeen"
    private const val KEY_ADVANCE_SECONDS = "advanceSeconds"

    private val _state = MutableStateFlow(AutomationState())
    val state: StateFlow<AutomationState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AutomationState()
        _state.value = AutomationState(
            guidePet = p.getBoolean(KEY_GUIDE_PET, d.guidePet),
            unattended = p.getBoolean(KEY_UNATTENDED, d.unattended),
            freeRun = p.getBoolean(KEY_FREE_RUN, d.freeRun),
            uninstallMismatch = p.getBoolean(KEY_UNINSTALL_MISMATCH, d.uninstallMismatch),
            freeRunWarningHidden = p.getBoolean(KEY_WARNING_HIDDEN, d.freeRunWarningHidden),
            appsNoticeSeen = p.getBoolean(KEY_APPS_NOTICE_SEEN, d.appsNoticeSeen),
            advanceSeconds = p.getInt(KEY_ADVANCE_SECONDS, d.advanceSeconds).coerceIn(ADVANCE_SECONDS_MIN, ADVANCE_SECONDS_MAX),
        )
        prefs = p
    }

    fun setGuidePet(value: Boolean) = update { it.copy(guidePet = value) }

    fun setUnattended(value: Boolean) = update { it.copy(unattended = value) }

    fun setFreeRun(value: Boolean) = update { it.copy(freeRun = value) }

    fun setUninstallMismatch(value: Boolean) = update { it.copy(uninstallMismatch = value) }

    fun hideFreeRunWarning() = update { it.copy(freeRunWarningHidden = true) }

    fun acknowledgeAppsNotice() = update { it.copy(appsNoticeSeen = true) }

    fun setAdvanceSeconds(value: Int) = update { it.copy(advanceSeconds = value.coerceIn(ADVANCE_SECONDS_MIN, ADVANCE_SECONDS_MAX)) }

    @Synchronized
    private fun update(transform: (AutomationState) -> AutomationState) {
        val next = transform(_state.value)
        _state.value = next
        prefs?.edit()
            ?.putBoolean(KEY_GUIDE_PET, next.guidePet)
            ?.putBoolean(KEY_UNATTENDED, next.unattended)
            ?.putBoolean(KEY_FREE_RUN, next.freeRun)
            ?.putBoolean(KEY_UNINSTALL_MISMATCH, next.uninstallMismatch)
            ?.putBoolean(KEY_WARNING_HIDDEN, next.freeRunWarningHidden)
            ?.putBoolean(KEY_APPS_NOTICE_SEEN, next.appsNoticeSeen)
            ?.putInt(KEY_ADVANCE_SECONDS, next.advanceSeconds)
            ?.apply()
    }
}
