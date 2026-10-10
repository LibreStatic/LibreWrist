package io.openshm.patcher.data

import androidx.annotation.StringRes
import io.openshm.patcher.R

/*
 * Baseline copy of the facts the screens show, mirrored from `catalogue/catalogue.json` (serial 3)
 * and `apks/inputs.json`. It exists so the app shell renders real data before the verified
 * catalogue loader lands; that loader replaces this object and the screens do not change.
 * `CatalogueDriftTest` fails when this file and the repo catalogue disagree.
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
)

enum class VariantChip { ApkNotBundle, NoDpi, MinAndroid11, MinAndroid14 }

/** One patch pattern (`catalogue.json` `patterns`): title from the catalogue, plain-language line from resources. */
data class PatchInfo(
    val id: String,
    val title: String,
    @StringRes val description: Int,
    /** Injected code (`watch-mod`, `alarm-fork`) rather than a smali pattern. */
    val injection: Boolean = false,
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
) {
    fun packageName(prefix: String): String = packageTemplate.replace("\${PKG_PREFIX}", prefix)
}

data class StockApp(
    val device: Device,
    val packageName: String,
    val label: String,
    val optional: Boolean,
    @StringRes val reason: Int,
)

enum class GrantKind { Permission, AppOp, SecureSetting, NotificationListener, BatteryExemption }

/** One entry of `catalogue.json` `grants`. [name] is the short permission/appop/setting/component name. */
data class Grant(val kind: GrantKind, val name: String, val optional: Boolean = false)

object BundledCatalogue {
    const val SERIAL = 4
    const val PREFIX_PLACEHOLDER = "\${PKG_PREFIX}"

    val inputs: List<InputApp> = listOf(
        InputApp(
            id = "shm-phone",
            label = "Samsung Health Monitor",
            versionName = "1.4.6.001",
            device = Device.Phone,
            sha256 = "3f20656e8c2ea34b95acb0b4d006b8aab3210fcc37cfb16abd2a7e4b91ca8524",
            packageName = "com.samsung.android.shealthmonitor",
            apkMirrorSlug = "samsung-electronics-co-ltd/samsung-health-monitor-2",
            variantChips = listOf(VariantChip.ApkNotBundle, VariantChip.NoDpi, VariantChip.MinAndroid11),
        ),
        InputApp(
            id = "shm-watch",
            label = "Samsung Health Monitor for Wear OS",
            versionName = "1.5.1.022",
            device = Device.Watch,
            sha256 = "a714e9a5950f456b8da4843bd6cdb19948a0c6fd9101b473e24beb3384a2c620",
            packageName = "com.samsung.android.shealthmonitor",
            apkMirrorSlug = "samsung-electronics-co-ltd/samsung-health-monitor-wear-os",
            variantChips = listOf(VariantChip.ApkNotBundle, VariantChip.NoDpi),
        ),
        InputApp(
            id = "alarm-watch",
            label = "Alarm for Wear OS",
            versionName = "1.3.05.9",
            device = Device.Watch,
            sha256 = "141832f22d7fb8bb7a6bb5e11695829015da6a33c5e19671a8695ecea276e8bd",
            packageName = "com.samsung.android.watch.alarm",
            apkMirrorSlug = "samsung-electronics-co-ltd/alarm-wear-os",
            variantChips = listOf(VariantChip.ApkNotBundle, VariantChip.NoDpi, VariantChip.MinAndroid14),
        ),
    )

    fun input(id: String): InputApp = inputs.first { it.id == id }

    /** The five outputs, in `installOrder` order. */
    val outputs: List<OutputApp> = listOf(
        OutputApp("shm-phone", "Samsung Health Monitor (patched)", Device.Phone, "com.samsung.android.shealthmonitors", "shm-phone"),
        OutputApp("shm-watch", "Samsung Health Monitor for Wear OS (patched)", Device.Watch, "com.samsung.android.shealthmonitors", "shm-watch"),
        OutputApp("bridge-watch", "Bridge for the watch", Device.Watch, "\${PKG_PREFIX}.shmbridge.watch", null),
        OutputApp("alarm-fork", "Phone alarms on the watch", Device.Watch, "\${PKG_PREFIX}.shmbridge.alarm", "alarm-watch"),
        OutputApp("bridge-phone", "Bridge for the phone", Device.Phone, "\${PKG_PREFIX}.shmbridge.phone", null),
    )

    fun output(id: String): OutputApp = outputs.first { it.id == id }

    val stockAppsToDisable: List<StockApp> = listOf(
        StockApp(
            device = Device.Watch,
            packageName = "com.samsung.android.shealthmonitor",
            label = "Samsung Health Monitor (stock)",
            optional = true,
            reason = R.string.stock_reason_shm,
        ),
    )

    val patchSets: List<PatchSet> = listOf(
        PatchSet(
            "shm-phone",
            listOf(
                PatchInfo("generic-manufacturer-check", "Manufacturer check", R.string.patch_generic_manufacturer_check),
                PatchInfo("generic-vendor-check", "Samsung SDK vendor check", R.string.patch_generic_vendor_check),
                PatchInfo("shm-min-age-22-to-16", "Minimum age 16", R.string.patch_shm_min_age_22_to_16),
                PatchInfo("shm-supported-type-ordinals", "Region ordinals", R.string.patch_shm_supported_type_ordinals),
                PatchInfo("shm-region-supportedtype", "Region check", R.string.patch_shm_region_supportedtype),
                PatchInfo("shm-region-countrylist", "Country list", R.string.patch_shm_region_countrylist),
                PatchInfo("shm-root-packagevalidator", "Certificate validation", R.string.patch_shm_root_packagevalidator),
                PatchInfo("shm-force-update-response", "Update check response", R.string.patch_shm_force_update_response),
                PatchInfo("shm-force-update-pref", "Forced update flag", R.string.patch_shm_force_update_pref),
                PatchInfo("shm-main-gates", "Start-up gates", R.string.patch_shm_main_gates),
                PatchInfo("shm-setup-network-gate", "Setup network gate", R.string.patch_shm_setup_network_gate),
                PatchInfo("shm-sleep-apnea-support", "Sleep apnea support", R.string.patch_shm_sleep_apnea_support),
                PatchInfo("phone-rebrand-gms", "Phone package rebrand", R.string.patch_phone_rebrand_gms),
            ),
        ),
        PatchSet(
            "shm-watch",
            listOf(
                PatchInfo("generic-manufacturer-check", "Manufacturer check", R.string.patch_generic_manufacturer_check),
                PatchInfo("watch-rebrand-coexist", "Watch package rebrand", R.string.patch_watch_rebrand_coexist),
                PatchInfo("watch-queries-health-service", "Health service visibility", R.string.patch_watch_queries_health_service),
                PatchInfo("watch-companion-vendor", "Companion vendor", R.string.patch_watch_companion_vendor),
                PatchInfo("watch-alarm-system-app", "Exact alarm scheduling", R.string.patch_watch_alarm_system_app),
                PatchInfo("watch-mobile-installed", "Phone app installed", R.string.patch_watch_mobile_installed),
                PatchInfo("watch-ecg-sensor-adapter", "ECG sensor", R.string.patch_watch_ecg_sensor_adapter),
                PatchInfo("watch-apnea-country", "Apnea country check", R.string.patch_watch_apnea_country),
                PatchInfo("watch-apnea-reeval-not-supported", "Apnea support re-evaluation", R.string.patch_watch_apnea_reeval_not_supported),
                PatchInfo("watch-mod", "Health Tracking module", R.string.patch_watch_mod, injection = true),
            ),
        ),
        PatchSet(
            "alarm-watch",
            listOf(
                PatchInfo("alarm-verify-symbols", "Link dependencies", R.string.patch_alarm_verify_symbols),
                PatchInfo("alarm-rebrand-manifest", "Fork package rebrand (manifest)", R.string.patch_alarm_rebrand_manifest),
                PatchInfo("alarm-rebrand-code", "Fork package rebrand (code)", R.string.patch_alarm_rebrand_code),
                PatchInfo("alarm-permissions-prune", "Privileged permissions", R.string.patch_alarm_permissions_prune),
                PatchInfo("alarm-notification-permissions", "Notification permissions", R.string.patch_alarm_notification_permissions),
                PatchInfo("alarm-queries-libs", "Queries and libraries", R.string.patch_alarm_queries_libs),
                PatchInfo("alarm-components-prune", "Vendor components", R.string.patch_alarm_components_prune),
                PatchInfo("alarm-provider-open", "Alarm provider access", R.string.patch_alarm_provider_open),
                PatchInfo("alarm-link-components", "Phone alarm link", R.string.patch_alarm_link_components),
                PatchInfo("alarm-appfunctions-drop", "Appfunctions tree", R.string.patch_alarm_appfunctions_drop),
                PatchInfo("alarm-semgesture-skip", "Gesture manager", R.string.patch_alarm_semgesture_skip),
                PatchInfo("alarm-gms-observer-drop", "GMS connection observer", R.string.patch_alarm_gms_observer_drop),
                PatchInfo("alarm-service-stop-on-destroy", "Stop on destroy", R.string.patch_alarm_service_stop_on_destroy),
                PatchInfo("alarm-force-sound-vibrate", "Sound and vibration", R.string.patch_alarm_force_sound_vibrate),
                PatchInfo("alarm-link-send", "Send alarms through the link", R.string.patch_alarm_link_send),
                PatchInfo("alarm-hide-launcher", "Hide launcher icon", R.string.patch_alarm_hide_launcher),
                PatchInfo("alarm-relabel", "App label", R.string.patch_alarm_relabel),
                PatchInfo("alarm-fork", "Alarm link module", R.string.patch_alarm_fork, injection = true),
            ),
        ),
    )

    fun patches(inputId: String): List<PatchInfo> = patchSets.first { it.inputId == inputId }.patches

    /** Grants per output id (`catalogue.json` `grants`); applied after each install. */
    val grants: Map<String, List<Grant>> = mapOf(
        "shm-phone" to emptyList(),
        "shm-watch" to listOf(
            Grant(GrantKind.AppOp, "SCHEDULE_EXACT_ALARM"),
            Grant(GrantKind.SecureSetting, "doze_always_on", optional = true),
        ),
        "bridge-watch" to listOf(
            Grant(GrantKind.Permission, "POST_NOTIFICATIONS"),
            Grant(GrantKind.Permission, "BLUETOOTH_CONNECT"),
            Grant(GrantKind.Permission, "READ_PHONE_STATE"),
            Grant(GrantKind.Permission, "ANSWER_PHONE_CALLS"),
            Grant(GrantKind.AppOp, "SCHEDULE_EXACT_ALARM"),
            Grant(GrantKind.AppOp, "USE_FULL_SCREEN_INTENT"),
            Grant(GrantKind.AppOp, "SYSTEM_ALERT_WINDOW"),
            Grant(GrantKind.AppOp, "MANAGE_ONGOING_CALLS"),
            Grant(GrantKind.NotificationListener, ".SamsungAlarmListener"),
            Grant(GrantKind.BatteryExemption, "deviceIdleWhitelist"),
        ),
        "alarm-fork" to listOf(
            Grant(GrantKind.Permission, "POST_NOTIFICATIONS"),
            Grant(GrantKind.AppOp, "USE_FULL_SCREEN_INTENT"),
            Grant(GrantKind.BatteryExemption, "deviceIdleWhitelist", optional = true),
        ),
        "bridge-phone" to listOf(
            Grant(GrantKind.Permission, "ANSWER_PHONE_CALLS"),
            Grant(GrantKind.Permission, "READ_PHONE_STATE"),
            Grant(GrantKind.Permission, "POST_NOTIFICATIONS"),
            Grant(GrantKind.Permission, "BLUETOOTH_CONNECT"),
            Grant(GrantKind.AppOp, "ACCESS_RESTRICTED_SETTINGS"),
            Grant(GrantKind.AppOp, "SYSTEM_ALERT_WINDOW"),
            Grant(GrantKind.NotificationListener, ".BridgeNotificationListener"),
        ),
    )

    /** Patch patterns only (injections excluded), the number the summary line reports. */
    fun patternCount(inputIds: Collection<String>): Int =
        patchSets.filter { it.inputId in inputIds }.sumOf { set -> set.patches.count { !it.injection } }
}
