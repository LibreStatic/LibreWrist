package io.openshm.patcher.device.guide

import androidx.annotation.StringRes
import io.openshm.patcher.R

enum class StepKind {
    /** Click the target. */
    Tap,

    /** Click a switch or radio; done when it is checked. */
    Toggle,

    /** Global back. */
    Back,

    /**
     * Start Developer options directly, right after they were unlocked. Back would not get there: About
     * phone was opened from LibreWrist, and where Settings lists Developer options differs per skin.
     */
    OpenDeveloperOptions,

    /** Read the six-digit code and the ip:port off the pairing dialog. */
    ReadPairingCode,

    /** No target: the user does something Tic must not see (the lock screen credential). */
    WaitForUser,

    /** The guide can't go on (debugging is blocked on this device): a sad bubble, then nothing. */
    Blocked,
}

/** When a step is complete besides "the next step's target appeared". */
sealed interface DoneWhen {
    /** Only the next step's target (or the window changing after Tic's click) ends the step. */
    data object NextTarget : DoneWhen

    /** The target's switch is checked. */
    data object Checked : DoneWhen

    /** The target was on screen and is gone (a dialog button the user pressed). */
    data object TargetGone : DoneWhen

    /** A toast with one of these texts was shown ([settingsRes] are Settings string names). */
    data class Toast(val settingsRes: List<String>, val fallbacks: List<String>) : DoneWhen
}

/**
 * One thing to do on one screen.
 *
 * [actor] is who is supposed to do it; a Tic step still falls back to the user whenever Tic is
 * not sure (see [GuideTracker]). [bubble] is the string shown while the step is current; the
 * label of [target] is its only argument. [expectedPackages] is where the screen must live before
 * Tic touches it (empty: anywhere). [optional] steps are skipped when their target is not there.
 */
data class GuideStep(
    val id: String,
    val target: Target?,
    val actor: Actor,
    @StringRes val bubble: Int,
    val example: GuideExample? = null,
    val done: DoneWhen = DoneWhen.NextTarget,
    val taps: Int = 1,
    val expectedPackages: Set<String> = emptySet(),
    val expectedClassHint: String? = null,
    val kind: StepKind = StepKind.Tap,
    val optional: Boolean = false,
)

data class GuideScript(val id: String, val steps: List<GuideStep>)

enum class PermissionKind { Overlay, UnknownSources, Battery, AppDetails, ListenerDetail, RestrictedSettings }

object GuideScripts {
    const val SETTINGS = "com.android.settings"
    const val GMS = "com.google.android.gms"
    private val settings = setOf(SETTINGS)
    private val settingsOrGms = setOf(SETTINGS, GMS)
    // Android 16 asks "Allow wireless debugging on this network?" from System UI, older releases from Settings.
    private val settingsOrSystemUi = setOf(SETTINGS, "com.android.systemui")

    private val allow = Target(
        settingsRes = emptyList(),
        fallbacks = listOf("Allow", "Permitir"),
        viewIds = listOf("android:id/button1"),
        role = Role.Button,
    )
    private val ok = Target(
        settingsRes = emptyList(),
        fallbacks = listOf("OK", "Aceptar"),
        viewIds = listOf("android:id/button1"),
        role = Role.Button,
    )

    // The Settings resource names below are best effort; each one is paired with English and Spanish
    // fallbacks so a wrong name only costs the "exact" flag (Tic then points instead of tapping).

    private val buildNumber = Target(
        settingsRes = listOf("build_number"),
        fallbacks = listOf(
            "Build number", "Número de compilación", "Número de versión", "Número de build",
            // Xiaomi keeps the tap target on the version row.
            "MIUI version", "OS version", "Versión de MIUI", "Versión del SO", "Versión de HyperOS",
        ),
    )
    private val softwareInfo = Target( // Samsung: Build number lives one level down.
        settingsRes = listOf("software_info"), // verify on device
        fallbacks = listOf("Software information", "Información de software", "Información del software"),
    )
    private val useDeveloperOptions = Target(
        settingsRes = listOf("development_settings_enable"),
        fallbacks = listOf("Use developer options", "Usar opciones para desarrolladores", "Usar opciones de desarrollador"),
        role = Role.Switch,
    )
    private val wirelessDebugging = Target(
        settingsRes = listOf("enable_adb_wireless"),
        fallbacks = listOf("Wireless debugging", "Depuración inalámbrica"),
    )
    private val useWirelessDebugging = Target(
        settingsRes = listOf("wireless_debugging_main_switch_title"), // verify on device
        fallbacks = listOf("Use wireless debugging", "Usar depuración inalámbrica"),
        role = Role.Switch,
    )
    private val pairWithCode = Target(
        settingsRes = listOf("adb_pair_method_code_title"),
        fallbacks = listOf(
            "Pair device with pairing code", "Vincular dispositivo con un código de vinculación",
            "Vincular dispositivo con código de vinculación", "Emparejar dispositivo con código de sincronización",
        ),
    )
    private val overlay = Target(
        settingsRes = listOf("permit_draw_overlay"),
        fallbacks = listOf(
            "Allow display over other apps", "Allow permission to display over other apps",
            "Permitir que se muestre sobre otras apps", "Permitir mostrar sobre otras apps",
        ),
        role = Role.Switch,
    )
    private val unknownSources = Target(
        settingsRes = listOf("external_source_switch_title"),
        fallbacks = listOf("Allow from this source", "Permitir desde esta fuente", "Permitir de esta fuente"),
        role = Role.Switch,
    )
    private val unrestricted = Target(
        settingsRes = listOf("manager_battery_usage_unrestricted_title"), // verify on device
        fallbacks = listOf("Unrestricted", "Sin restricciones"),
        role = Role.Switch,
    )
    // Android 15+: the app's battery page shows this two-target row; its text opens Optimized / Unrestricted.
    private val allowBackground = Target(
        settingsRes = listOf("manager_battery_usage_allow_background_usage_title"),
        fallbacks = listOf("Allow background usage", "Permitir uso en segundo plano", "Permitir el uso en segundo plano"),
    )
    private val notificationAccess = Target(
        settingsRes = listOf("notification_access_detail_switch", "notification_listener_allow_title"),
        fallbacks = listOf(
            "Allow notification access", "Use notification access",
            "Permitir acceso a notificaciones", "Permitir el acceso a las notificaciones",
        ),
        role = Role.Switch,
    )
    private val moreOptions = Target(
        settingsRes = emptyList(),
        fallbacks = listOf("More options", "Más opciones"),
    )
    private val allowRestricted = Target(
        settingsRes = listOf("app_restricted_settings_lockscreen_title", "restricted_settings_allow"),
        fallbacks = listOf("Allow restricted settings", "Permitir configuración restringida", "Permitir ajustes restringidos"),
    )
    private val storageAndCache = Target(
        settingsRes = listOf("storage_settings_for_app"),
        fallbacks = listOf("Storage & cache", "Almacenamiento y caché", "Storage", "Almacenamiento"),
    )
    private val manageSpace = Target(
        settingsRes = listOf("manage_space_text"),
        fallbacks = listOf("Manage space", "Administrar espacio", "Gestionar espacio"),
    )
    // Android 16 labels the button that opens Play services' own space screen "Clear storage".
    private val clearStorage = Target(
        settingsRes = listOf("clear_user_data_text"),
        fallbacks = listOf("Clear storage", "Borrar almacenamiento", "Clear data", "Borrar datos"),
    )
    private val clearAllData = Target(
        settingsRes = emptyList(),
        fallbacks = listOf("Clear all data", "Borrar todos los datos"),
        role = Role.Button,
    )

    private val developerToast = DoneWhen.Toast(
        settingsRes = listOf("show_dev_on", "show_dev_already"),
        fallbacks = listOf(
            "You are now a developer!", "No need, you are already a developer.",
            "¡Ya eres desarrollador!", "No es necesario, ya eres desarrollador.",
        ),
    )

    private fun blocked() = GuideScript(
        "debugging-disallowed",
        listOf(GuideStep("blocked", null, Actor.User, R.string.guide_debugging_disallowed, kind = StepKind.Blocked)),
    )

    private fun back(id: String, @StringRes bubble: Int = R.string.guide_back_done) =
        GuideStep(id, null, Actor.Tic, bubble, kind = StepKind.Back)

    private fun pin() = GuideStep("pin", null, Actor.User, R.string.guide_pin, kind = StepKind.WaitForUser)

    private fun unlockSteps() = listOf(
        GuideStep(
            "software-info", softwareInfo, Actor.Tic, R.string.guide_software_info,
            expectedPackages = settings, optional = true,
        ),
        GuideStep(
            "build-number", buildNumber, Actor.Tic, R.string.guide_build_number_hello,
            example = GuideExample("Build number", "Tap it seven times to unlock the developer options"),
            done = developerToast, taps = 7, expectedPackages = settings,
        ),
        pin(),
        GuideStep("open-developer-options", null, Actor.Tic, R.string.guide_unlocked_opening, kind = StepKind.OpenDeveloperOptions),
    )

    /** Opened on About phone (`ACTION_DEVICE_INFO_SETTINGS`): unlock the hidden developer options. */
    fun developerOptionsUnlock(env: GuideEnv): GuideScript =
        if (env.debuggingDisallowed) blocked() else GuideScript("developer-options-unlock", unlockSteps())

    /** Opened on Developer options: enable Wireless debugging and read the pairing code. */
    fun wirelessDebugging(env: GuideEnv): GuideScript {
        if (env.debuggingDisallowed) return blocked()
        val steps = buildList {
            if (!env.developerOptionsEnabled) addAll(unlockSteps())
            add(
                GuideStep(
                    "developer-options-switch", useDeveloperOptions, Actor.Tic, R.string.guide_devopts_on,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle, optional = true,
                ),
            )
            add(
                GuideStep(
                    "wireless-debugging", wirelessDebugging, Actor.Tic, R.string.guide_wireless_tap,
                    example = GuideExample("Wireless debugging", "Debug mode when Wi-Fi is connected"),
                    expectedPackages = settings,
                ),
            )
            add(
                GuideStep(
                    "wireless-switch", useWirelessDebugging, Actor.Tic, R.string.guide_wireless_switch,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
                ),
            )
            // Security confirmation ("trust this network"): always the user's tap.
            add(
                GuideStep(
                    "wireless-allow", allow, Actor.User, R.string.guide_wireless_allow,
                    done = DoneWhen.TargetGone, expectedPackages = settingsOrSystemUi,
                ),
            )
            add(
                GuideStep(
                    "pair-with-code", pairWithCode, Actor.Tic, R.string.guide_pair_tap,
                    example = GuideExample(
                        "Pair device with pairing code", "Pair new devices using a six-digit code",
                    ),
                    expectedPackages = settings,
                ),
            )
            add(
                GuideStep(
                    "read-code", null, Actor.Tic, R.string.guide_code_searching,
                    expectedPackages = settings, kind = StepKind.ReadPairingCode,
                ),
            )
        }
        return GuideScript("wireless-debugging", steps)
    }

    /**
     * One permission screen. [label] names what the user must find on an AppDetails screen (the
     * script only points there); it is unused for the other kinds. [appLabel] is the app the page is
     * for: the overlay and battery pages open on a list of all apps on recent releases, so Tic opens
     * its row first (the step is passed over where the app's own page opens directly).
     */
    fun permission(kind: PermissionKind, label: String? = null, appLabel: String? = null): GuideScript = when (kind) {
        PermissionKind.Overlay -> GuideScript(
            "permission-overlay",
            listOfNotNull(
                appRow("overlay-app", appLabel),
                GuideStep(
                    "overlay-switch", overlay, Actor.Tic, R.string.guide_perm_overlay,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
                ),
                back("overlay-back"),
            ),
        )
        PermissionKind.UnknownSources -> GuideScript(
            "permission-unknown-sources",
            listOf(
                GuideStep(
                    "unknown-sources-switch", unknownSources, Actor.Tic, R.string.guide_perm_unknown,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
                ),
                back("unknown-sources-back"),
            ),
        )
        PermissionKind.Battery -> GuideScript(
            "permission-battery",
            listOfNotNull(
                appRow("battery-app", appLabel),
                // Passed over where Unrestricted is already on the page.
                GuideStep("battery-background", allowBackground, Actor.Tic, R.string.guide_tic_opens, expectedPackages = settings),
                GuideStep(
                    "battery-unrestricted", unrestricted, Actor.Tic, R.string.guide_perm_battery,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
                ),
                back("battery-back"),
            ),
        )
        PermissionKind.AppDetails -> GuideScript(
            "permission-app-details",
            listOf(
                GuideStep(
                    "app-details-point",
                    Target(emptyList(), listOf(label ?: "Permissions", "Permisos").distinct()),
                    Actor.User, R.string.guide_perm_app_details,
                    done = DoneWhen.TargetGone, expectedPackages = settings,
                ),
            ),
        )
        PermissionKind.ListenerDetail -> GuideScript(
            "permission-listener",
            listOf(
                GuideStep(
                    "listener-switch", notificationAccess, Actor.Tic, R.string.guide_perm_listener,
                    done = DoneWhen.Checked, expectedPackages = settings, kind = StepKind.Toggle,
                ),
                // The system "Allow notification access?" confirmation: security, so the user's.
                GuideStep(
                    "listener-confirm", allow, Actor.User, R.string.guide_perm_listener_confirm,
                    done = DoneWhen.TargetGone, expectedPackages = settings,
                ),
                back("listener-back"),
            ),
        )
        // Everything here is the user's, and so is the PIN.
        PermissionKind.RestrictedSettings -> GuideScript(
            "permission-restricted-settings",
            listOf(
                GuideStep(
                    "restricted-menu", moreOptions, Actor.User, R.string.guide_restricted_menu,
                    done = DoneWhen.TargetGone, expectedPackages = settings,
                ),
                GuideStep(
                    "restricted-allow", allowRestricted, Actor.User, R.string.guide_restricted_allow,
                    done = DoneWhen.TargetGone, expectedPackages = settings,
                ),
                pin(),
                back("restricted-back"),
            ),
        )
    }

    private fun appRow(id: String, appLabel: String?): GuideStep? = appLabel?.let {
        GuideStep(
            id, Target(emptyList(), emptyList(), exactLabels = listOf(it)), Actor.Tic, R.string.guide_tic_opens,
            expectedPackages = settings,
        )
    }

    /** App info of Google Play services: storage, manage space, clear all data (the user's). */
    fun playServicesClearData(): GuideScript = GuideScript(
        "play-services-clear-data",
        listOf(
            GuideStep("storage", storageAndCache, Actor.Tic, R.string.guide_tic_opens, expectedPackages = settings),
            GuideStep("manage-space", manageSpace, Actor.Tic, R.string.guide_tic_opens, expectedPackages = settingsOrGms),
            // Passed over once Play services' screen is up; a "Clear…" button is never Tic's to tap.
            GuideStep(
                "clear-storage", clearStorage, Actor.User, R.string.guide_user_taps,
                done = DoneWhen.TargetGone, expectedPackages = settings,
            ),
            // Destructive: Tic only points.
            GuideStep(
                "clear-all-data", clearAllData, Actor.User, R.string.guide_gms_clear,
                done = DoneWhen.TargetGone, expectedPackages = settingsOrGms,
            ),
            GuideStep(
                "clear-all-data-ok", ok, Actor.User, R.string.guide_gms_confirm,
                done = DoneWhen.TargetGone, expectedPackages = settingsOrGms,
            ),
        ),
    )
}
