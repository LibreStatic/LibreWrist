package io.openshm.patcher.data

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
    /** The ABIs of the expected build, for the on-screen target line. */
    val abis: List<String> = emptyList(),
) {
    /** The APKMirror page listing this app's releases. */
    val mirrorUrl: String get() = "https://www.apkmirror.com/apk/$apkMirrorSlug/"
}

enum class VariantChip { ApkNotBundle, NoDpi, MinAndroid11, MinAndroid14 }

/**
 * One patch pattern (`catalogue.json` `patterns`): title from the catalogue; [description] is the
 * plain-language line from resources, or the catalogue description when no resource exists.
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
    /** The install list asks the user to finish this app's own setup after it is installed (`then: userOnboarding`). */
    val userOnboarding: Boolean = false,
) {
    fun packageName(prefix: String): String = packageTemplate.replace("\${PKG_PREFIX}", prefix)
}

data class StockApp(
    val device: Device,
    val packageName: String,
    val label: String,
    val optional: Boolean,
    /** Plain-language reason from resources, or the catalogue text when no resource exists. */
    val reason: String,
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
)
