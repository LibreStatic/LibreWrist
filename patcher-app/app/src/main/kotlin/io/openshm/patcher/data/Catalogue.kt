package io.openshm.patcher.data

import io.openshm.patcher.engine.OutputStart

/*
 * The app-side model of the facts the screens show. Nothing here is hand-written data: the verified
 * catalogue is loaded and mapped into these classes by `AppCatalogue.kt`.
 */

enum class Device { Phone, Watch }

/** An official input APK the user has to supply (`apks/inputs.json`). */
data class InputApp(
    val id: String,
    val label: String,
    val versionName: String,
    val device: Device,
    val sha256: String,
    val packageName: String,
    val apkMirrorSlug: String,
    /** Variant facts shown as chips on the card. */
    val variantChips: List<VariantChip>,
    /** The APKMirror release path segment of this exact version (`obtain.apkmirrorRelease`), when known. */
    val apkMirrorRelease: String? = null,
    /** False when this version is not published on APKMirror (`obtain.onApkMirror`). */
    val onApkMirror: Boolean = true,
    /** True when this version is on YingYongBao (`obtain.yyb`); off until a catalogue entry opts in. */
    val onYyb: Boolean = false,
    /** The lowest Android SDK the app runs on (`minSdk`); 0 when unknown. */
    val minSdk: Int = 0,
    /** The ABIs of the expected build, for the on-screen target line. */
    val abis: List<String> = emptyList(),
) {
    /** The APKMirror page listing this app's releases. */
    val mirrorUrl: String get() = "https://www.apkmirror.com/apk/$apkMirrorSlug/"
}

enum class VariantChip { ApkNotBundle, NoDpi, MinAndroid11, MinAndroid14 }

/**
 * One patch pattern (`catalogue.json` `patterns`): title from the catalogue; [description] is the
 * plain-language line from the catalogue strings, or the pattern's own description when there is none.
 */
data class PatchInfo(
    val id: String,
    val title: String,
    val description: String,
    /** Injected code (`watch-mod`, `alarm-fork`) rather than a smali pattern. */
    val injection: Boolean = false,
    /** The user may switch this pattern off (`optional` in the catalogue). Injections are always required. */
    val optional: Boolean = false,
    /** Patterns sharing a group are switched on or off together, across apps. */
    val optionGroup: String? = null,
)

/** The patches that apply to one input, in profile order. */
data class PatchSet(val inputId: String, val patches: List<PatchInfo>)

/** An app the build produces (`catalogue.json` `outputs`). */
data class OutputApp(
    val id: String,
    val label: String,
    val device: Device,
    /** Package name; `${PKG_PREFIX}` is replaced by the chosen prefix. */
    val packageTemplate: String,
    val inputId: String?,
    /** The input this app is built from instead of [inputId] when China mode is on (`chinaFrom`), or null. */
    val chinaInputId: String? = null,
    /** One plain-language line on what this app is for (the Welcome changes list); empty when the catalogue has none. */
    val purpose: String = "",
    /** How the installed app is started (`start`), or null when it needs no start. */
    val start: OutputStart? = null,
    /** The app talks to the companion app (`companionLink`). */
    val companionLink: Boolean = false,
    /** The app may be signed by a key other than the catalogue's own (`knownSigner`). */
    val knownSigner: Boolean = false,
    /** Content authority template where the app publishes its status (`statusAuthority`), or null. */
    val statusAuthority: String? = null,
    /** Copies under other package prefixes are leftovers the install removes (`singleCopy`): bridges and Samsung Health+. */
    val singleCopy: Boolean = false,
    /** The install list asks the user to finish this app's own setup after it is installed (`then: userOnboarding`). */
    val userOnboarding: Boolean = false,
) {
    fun packageName(prefix: String): String = packageTemplate.replace("\${PKG_PREFIX}", prefix)

    /** The action of [start] with the chosen prefix, or null when the app has no start. */
    fun startAction(prefix: String): String? = start?.action?.replace("\${PKG_PREFIX}", prefix)

    /** [statusAuthority] with the chosen prefix. */
    fun statusAuthority(prefix: String): String? = statusAuthority?.replace("\${PKG_PREFIX}", prefix)
}

/** A user-facing app (`catalogue.json` `apps`): one switch on Acquire, one or more outputs; [icon] is an `APP_ICONS` symbol. */
data class AppGroup(
    val id: String,
    val title: String,
    val summary: String,
    val optional: Boolean,
    val icon: String,
    val outputIds: List<String>,
)

data class StockApp(
    val device: Device,
    val packageName: String,
    val label: String,
    val optional: Boolean,
    /** Plain-language reason from the catalogue strings, or the catalogue reason when there is no translation. */
    val reason: String,
    /** Id of the output that replaces this stock app, or null. */
    val replacedBy: String? = null,
)

enum class GrantKind { Permission, AppOp, SecureSetting, GlobalSetting, NotificationListener, BatteryExemption }

/**
 * One entry of `catalogue.json` `grants`. [name] is the short permission/appop/setting/component name;
 * [engine] is the catalogue entry itself, which the installer executes.
 */
data class Grant(
    val kind: GrantKind,
    val name: String,
    val optional: Boolean = false,
    val engine: io.openshm.patcher.engine.Grant? = null,
    /** Plain-language reason in the user's language (the `why` key), or null when the catalogue gives none. */
    val why: String? = null,
)
