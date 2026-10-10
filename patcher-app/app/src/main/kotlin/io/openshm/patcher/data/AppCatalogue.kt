package io.openshm.patcher.data

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.VisibleForTesting
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.R
import io.openshm.patcher.engine.Catalogue
import io.openshm.patcher.engine.CatalogueException
import io.openshm.patcher.engine.CatalogueVerifier
import io.openshm.patcher.engine.CatalogueInput
import io.openshm.patcher.engine.CatalogueOutput
import io.openshm.patcher.engine.OutputSource

/**
 * The verified catalogue as the screens see it: the engine model mapped into the app model classes
 * ([InputApp], [OutputApp], [PatchInfo], [Grant], [StockApp]). User-facing text comes from string
 * resources, resolved once through [resolve] (the catalogue text is only the fallback).
 */
class LoadedCatalogue(val engine: Catalogue, private val resolve: (Int) -> String) {
    val serial: Long get() = engine.serial

    val inputs: List<InputApp> = engine.inputs.map(::mapInput)

    fun input(id: String): InputApp = inputs.first { it.id == id }

    /** The outputs, in the catalogue `installOrder` order. */
    val outputs: List<OutputApp> = engine.installOrder.map { step ->
        mapOutput(engine.outputs.first { it.id == step.output })
    }

    fun output(id: String): OutputApp = outputs.first { it.id == id }

    /** Patches per input that some output is built from, in profile order, injections last. */
    val patchSets: List<PatchSet> = engine.outputs
        .mapNotNull { (it.from as? OutputSource.Patched) }
        .distinctBy { it.input }
        .map { from -> PatchSet(from.input, patchesOf(from)) }

    fun patches(inputId: String): List<PatchInfo> = patchSets.first { it.inputId == inputId }.patches

    /** Grants per output id (`catalogue.json` `grants`); applied after each install. */
    val grants: Map<String, List<Grant>> = engine.grants.mapValues { (_, list) -> list.map(::mapGrant) }

    val stockAppsToDisable: List<StockApp> = engine.stockAppsToDisable.map { stock ->
        StockApp(
            device = device(stock.device),
            packageName = stock.packageName,
            label = STOCK_LABELS[stock.packageName]?.let(resolve) ?: stock.packageName,
            optional = stock.optional,
            reason = STOCK_REASONS[stock.packageName]?.let(resolve) ?: stock.reason,
        )
    }

    /** Patch patterns only (injections excluded), the number the summary line reports. */
    fun patternCount(inputIds: Collection<String>): Int =
        patchSets.filter { it.inputId in inputIds }.sumOf { set -> set.patches.count { !it.injection } }

    private fun patchesOf(from: OutputSource.Patched): List<PatchInfo> {
        val profile = engine.profile(from.profile)
        val patterns = profile.families[from.input].orEmpty().map { id ->
            val pattern = engine.patterns.getValue(id)
            PatchInfo(id, pattern.title, PATCH_DESCRIPTIONS[id]?.let(resolve) ?: pattern.description)
        }
        val injections = profile.inject[from.input].orEmpty().map { id ->
            val known = INJECTIONS[id]
            PatchInfo(id, known?.first ?: id, known?.second?.let(resolve).orEmpty(), injection = true)
        }
        return patterns + injections
    }

    private fun mapInput(input: CatalogueInput): InputApp {
        val obtain = input.raw["obtain"] as? Map<*, *>
        return InputApp(
            id = input.id,
            label = input.label,
            versionName = input.versionName,
            device = device(input.target),
            sha256 = input.sha256,
            packageName = input.packageName,
            apkMirrorSlug = obtain?.get("apkmirror") as? String ?: "",
            variantChips = chipsOf(input.raw),
        )
    }

    private fun mapOutput(out: CatalogueOutput) = OutputApp(
        id = out.id,
        label = OUTPUT_LABELS[out.id]?.let(resolve) ?: out.id,
        device = device(out.device),
        packageTemplate = out.packageName,
        inputId = (out.from as? OutputSource.Patched)?.input,
    )

    private fun mapGrant(g: io.openshm.patcher.engine.Grant): Grant {
        val (kind, name) = when (g.kind) {
            "permission" -> GrantKind.Permission to g.fields.getValue("name").substringAfterLast('.')
            "appop" -> GrantKind.AppOp to g.fields.getValue("op")
            "notificationListener" -> GrantKind.NotificationListener to g.fields.getValue("component")
            "deviceIdleWhitelist" -> GrantKind.BatteryExemption to "deviceIdleWhitelist"
            "secureSetting" -> GrantKind.SecureSetting to g.fields.getValue("key")
            else -> throw IllegalArgumentException("unknown grant kind '${g.kind}'")
        }
        return Grant(kind, name, g.optional, g)
    }

    companion object {
        /**
         * Chips from the catalogue facts: `format == "apk"` shows ApkNotBundle, `dpi == "nodpi"` shows
         * NoDpi. The minimum-Android chip reproduces what the app has always shown: minSdk 34 or
         * newer is "Android 14+", minSdk 31 to 33 is "Android 11+" (the phone app), and anything older
         * (the Wear OS app, minSdk 30) shows no chip.
         */
        internal fun chipsOf(raw: Map<String, Any?>): List<VariantChip> = buildList {
            if (raw["format"] == "apk") add(VariantChip.ApkNotBundle)
            if (raw["dpi"] == "nodpi") add(VariantChip.NoDpi)
            val minSdk = (raw["minSdk"] as? Long) ?: 0L
            if (minSdk >= 34) add(VariantChip.MinAndroid14) else if (minSdk >= 31) add(VariantChip.MinAndroid11)
        }

        private fun device(name: String) = if (name == "watch") Device.Watch else Device.Phone

        private val OUTPUT_LABELS = mapOf<String, Int>(
            "shm-phone" to R.string.output_shm_phone,
            "shm-watch" to R.string.output_shm_watch,
            "bridge-watch" to R.string.output_bridge_watch,
            "alarm-fork" to R.string.output_alarm_fork,
            "bridge-phone" to R.string.output_bridge_phone,
        )

        private val STOCK_LABELS = mapOf<String, Int>(
            "com.samsung.android.shealthmonitor" to R.string.stock_label_shm,
        )

        private val STOCK_REASONS = mapOf<String, Int>(
            "com.samsung.android.shealthmonitor" to R.string.stock_reason_shm,
        )

        /** Injected code (profile `inject`): title and plain-language line, not catalogue patterns. */
        private val INJECTIONS = mapOf<String, Pair<String, Int>>(
            "watch-mod" to ("Health Tracking module" to R.string.patch_watch_mod),
            "alarm-fork" to ("Alarm link module" to R.string.patch_alarm_fork),
        )

        private val PATCH_DESCRIPTIONS = mapOf<String, Int>(
        "generic-manufacturer-check" to R.string.patch_generic_manufacturer_check,
        "generic-vendor-check" to R.string.patch_generic_vendor_check,
        "shm-min-age-22-to-16" to R.string.patch_shm_min_age_22_to_16,
        "shm-supported-type-ordinals" to R.string.patch_shm_supported_type_ordinals,
        "shm-region-supportedtype" to R.string.patch_shm_region_supportedtype,
        "shm-region-countrylist" to R.string.patch_shm_region_countrylist,
        "shm-root-packagevalidator" to R.string.patch_shm_root_packagevalidator,
        "shm-force-update-response" to R.string.patch_shm_force_update_response,
        "shm-force-update-pref" to R.string.patch_shm_force_update_pref,
        "shm-main-gates" to R.string.patch_shm_main_gates,
        "shm-setup-network-gate" to R.string.patch_shm_setup_network_gate,
        "shm-sleep-apnea-support" to R.string.patch_shm_sleep_apnea_support,
        "phone-rebrand-gms" to R.string.patch_phone_rebrand_gms,
        "watch-rebrand-coexist" to R.string.patch_watch_rebrand_coexist,
        "watch-queries-health-service" to R.string.patch_watch_queries_health_service,
        "watch-companion-vendor" to R.string.patch_watch_companion_vendor,
        "watch-alarm-system-app" to R.string.patch_watch_alarm_system_app,
        "watch-mobile-installed" to R.string.patch_watch_mobile_installed,
        "watch-ecg-sensor-adapter" to R.string.patch_watch_ecg_sensor_adapter,
        "watch-apnea-country" to R.string.patch_watch_apnea_country,
        "watch-apnea-reeval-not-supported" to R.string.patch_watch_apnea_reeval_not_supported,
        "alarm-verify-symbols" to R.string.patch_alarm_verify_symbols,
        "alarm-rebrand-manifest" to R.string.patch_alarm_rebrand_manifest,
        "alarm-rebrand-code" to R.string.patch_alarm_rebrand_code,
        "alarm-permissions-prune" to R.string.patch_alarm_permissions_prune,
        "alarm-notification-permissions" to R.string.patch_alarm_notification_permissions,
        "alarm-queries-libs" to R.string.patch_alarm_queries_libs,
        "alarm-components-prune" to R.string.patch_alarm_components_prune,
        "alarm-provider-open" to R.string.patch_alarm_provider_open,
        "alarm-link-components" to R.string.patch_alarm_link_components,
        "alarm-appfunctions-drop" to R.string.patch_alarm_appfunctions_drop,
        "alarm-semgesture-skip" to R.string.patch_alarm_semgesture_skip,
        "alarm-gms-observer-drop" to R.string.patch_alarm_gms_observer_drop,
        "alarm-service-stop-on-destroy" to R.string.patch_alarm_service_stop_on_destroy,
        "alarm-force-sound-vibrate" to R.string.patch_alarm_force_sound_vibrate,
        "alarm-link-send" to R.string.patch_alarm_link_send,
        "alarm-hide-launcher" to R.string.patch_alarm_hide_launcher,
        "alarm-relabel" to R.string.patch_alarm_relabel,
        )
    }
}

/** Holder of the catalogue the whole app reads; installed once by `PatcherApplication`. */
object AppCatalogue {
    @Volatile private var loaded: LoadedCatalogue? = null

    val current: LoadedCatalogue
        get() = loaded ?: error("the bundled catalogue is not loaded (AppCatalogue.install was never called)")

    fun install(catalogue: LoadedCatalogue) {
        loaded = catalogue
    }

    /** Loads the bundled catalogue from [context] unless one is installed already (previews, tests). */
    fun ensure(context: Context) {
        if (loaded == null) install(loadBundled(context.applicationContext.assets, context.applicationContext::getString))
    }

    private const val DIR = "catalogue"

    /**
     * Reads the three bundled assets and loads them through the verifying parser. A bundled catalogue
     * that does not verify is a broken build, so this throws instead of falling back to anything.
     */
    fun loadBundled(assets: AssetManager, resolve: (Int) -> String): LoadedCatalogue {
        fun read(name: String) = assets.open("$DIR/$name").use { it.readBytes() }
        return fromBytes(
            read("catalogue.json"), read("catalogue.json.sig"), String(read("catalogue.pub"), Charsets.UTF_8),
            BuildConfig.VERSION_CODE, resolve,
        )
    }

    /** Verifies [bytes] against [signature] and [publicKeyPem], parses them and checks the app version. */
    @VisibleForTesting
    fun fromBytes(
        bytes: ByteArray,
        signature: ByteArray,
        publicKeyPem: String,
        appVersionCode: Int,
        resolve: (Int) -> String,
    ): LoadedCatalogue {
        val engine = try {
            Catalogue.parseSigned(bytes, signature, CatalogueVerifier.parsePublicKey(publicKeyPem))
        } catch (e: RuntimeException) {
            throw IllegalStateException("the bundled catalogue failed verification (broken build): ${e.message}", e)
        }
        check(engine.minAppVersion <= appVersionCode) {
            "the bundled catalogue needs app version ${engine.minAppVersion}, this is $appVersionCode (broken build)"
        }
        return LoadedCatalogue(engine, resolve)
    }
}
