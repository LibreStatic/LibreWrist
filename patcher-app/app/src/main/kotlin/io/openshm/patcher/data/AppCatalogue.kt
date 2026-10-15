package io.openshm.patcher.data

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.VisibleForTesting
import io.openshm.patcher.BuildConfig
import io.openshm.patcher.engine.Catalogue
import io.openshm.patcher.engine.CatalogueException
import io.openshm.patcher.engine.CatalogueVerifier
import io.openshm.patcher.engine.CatalogueInput
import io.openshm.patcher.engine.CatalogueOutput
import io.openshm.patcher.engine.CataloguePlatform
import io.openshm.patcher.engine.OutputSource
import java.io.File
import java.util.Locale

/**
 * The verified catalogue as the screens see it: the engine model mapped into the app model classes
 * ([InputApp], [OutputApp], [AppGroup], [PatchInfo], [Grant], [StockApp]). User-facing text comes from
 * the catalogue `strings` table, resolved once for [locales] through [text] (the catalogue's own
 * title or description is only the fallback).
 */
class LoadedCatalogue(val engine: Catalogue, val locales: List<Locale>) {
    val serial: Long get() = engine.serial

    val text = CatalogueText(engine.strings, locales)

    /** Platform facts (companion app, Health Platform service, log packages, settings to restore). */
    val platform: CataloguePlatform get() = engine.platform

    /** The user-facing apps, in the catalogue `apps` order; every output belongs to exactly one. */
    val apps: List<AppGroup> = engine.apps.map { app ->
        AppGroup(
            id = app.id,
            title = text.text("app.${app.id}.title", app.id),
            summary = text.text("app.${app.id}.summary", ""),
            optional = app.optional,
            icon = app.icon,
            outputIds = app.outputs,
        )
    }

    /**
     * Watch outputs whose install changes more than their own package, so reverting one also undoes that:
     * the output that replaces a stock app (`replacedBy`) and any output with a `globalSetting` grant.
     */
    val watchExtraOutputs: Set<String> = buildSet {
        engine.stockAppsToDisable.mapNotNullTo(this) { it.replacedBy }
        engine.grants.forEach { (id, list) -> if (list.any { it.kind == "globalSetting" }) add(id) }
    }.filterTo(mutableSetOf()) { id -> engine.outputs.any { it.id == id && it.device == "watch" } }

    /** Global settings a grant appended an entry to, as setting key to the entries to remove again. */
    val globalSettingEntries: Map<String, List<String>> = engine.grants.values.flatten()
        .filter { it.kind == "globalSetting" }
        .groupBy({ it.fields.getValue("key") }, { it.fields.getValue("value") })
        .mapValues { (_, entries) -> entries.distinct() }

    /**
     * The outputs a setup builds, in `installOrder` order: those of every required app plus every
     * optional app not in [skippedApps], limited to outputs whose official input can run on a
     * phone or on a watch with SDK [watchSdk] (the input's `minSdk` is not above it).
     */
    fun selectedOutputs(watchSdk: Int, skippedApps: Set<String>): List<OutputApp> {
        val inScope = apps.filter { !it.optional || it.id !in skippedApps }.flatMap { it.outputIds }.toSet()
        return outputs.filter { out ->
            out.id in inScope && (out.inputId?.let(::input)?.let { it.device == Device.Phone || it.minSdk <= watchSdk } ?: true)
        }
    }

    /** The app that groups output [outputId]. */
    fun appOf(outputId: String): AppGroup? = apps.firstOrNull { outputId in it.outputIds }

    val inputs: List<InputApp> = engine.inputs.map(::mapInput)

    fun input(id: String): InputApp = inputs.first { it.id == id }

    fun hasInput(id: String): Boolean = inputs.any { it.id == id }

    /** The outputs, in the catalogue `installOrder` order. */
    val outputs: List<OutputApp> = engine.installOrder.map { step ->
        mapOutput(engine.outputs.first { it.id == step.output }).copy(userOnboarding = step.then == "userOnboarding")
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
            label = text.text("stock.${stock.packageName}.label", stock.packageName),
            optional = stock.optional,
            reason = text.text("stock.${stock.packageName}.reason", stock.reason),
            replacedBy = stock.replacedBy,
        )
    }

    /**
     * The patterns a build skips for the user's choice [disabled]: only known optional patterns count
     * (an id a catalogue update removed or made required is dropped, so it can never fail a build), and
     * an option group is skipped as a whole.
     */
    fun effectiveDisabled(disabled: Set<String>): Set<String> {
        val chosen = disabled.filter { engine.patterns[it]?.optional == true }.toSet()
        val groups = chosen.mapNotNull { engine.patterns.getValue(it).optionGroup }.toSet()
        return chosen + engine.patterns.values.filter { it.optionGroup in groups }.map { it.id }
    }

    /** Patch patterns that will be applied (injections excluded, [disabled] ones left out), the number the summary line reports. */
    fun patternCount(inputIds: Collection<String>, disabled: Set<String> = emptySet()): Int {
        val skipped = effectiveDisabled(disabled)
        return patchSets.filter { it.inputId in inputIds }.sumOf { set -> set.patches.count { !it.injection && it.id !in skipped } }
    }

    private fun patchesOf(from: OutputSource.Patched): List<PatchInfo> {
        val profile = engine.profile(from.profile)
        val patterns = profile.families[from.input].orEmpty().map { id ->
            val pattern = engine.patterns.getValue(id)
            PatchInfo(
                id, pattern.title, text.text("pattern.$id.description", pattern.description),
                optional = pattern.optional, optionGroup = pattern.optionGroup,
            )
        }
        val injections = profile.inject[from.input].orEmpty().map { id ->
            PatchInfo(id, text.text("inject.$id.title", id), text.text("inject.$id.description", ""), injection = true)
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
            apkMirrorRelease = (obtain?.get("apkmirrorRelease") as? String)?.trim('/')?.takeIf { it.isNotEmpty() },
            onApkMirror = obtain?.get("onApkMirror") != false,
            minSdk = (input.raw["minSdk"] as? Number)?.toInt() ?: 0,
            abis = (input.raw["abis"] as? List<*>)?.filterIsInstance<String>().orEmpty(),
        )
    }

    private fun mapOutput(out: CatalogueOutput) = OutputApp(
        id = out.id,
        label = text.text("output.${out.id}.label", out.id),
        device = device(out.device),
        packageTemplate = out.packageName,
        inputId = (out.from as? OutputSource.Patched)?.input,
        purpose = text.text("output.${out.id}.purpose", ""),
        start = out.start,
        companionLink = out.companionLink,
        knownSigner = out.knownSigner,
        statusAuthority = out.statusAuthority,
        singleCopy = out.singleCopy,
    )

    private fun mapGrant(g: io.openshm.patcher.engine.Grant): Grant {
        val (kind, name) = when (g.kind) {
            "permission" -> GrantKind.Permission to g.fields.getValue("name").substringAfterLast('.')
            "appop" -> GrantKind.AppOp to g.fields.getValue("op")
            "notificationListener" -> GrantKind.NotificationListener to g.fields.getValue("component")
            "deviceIdleWhitelist" -> GrantKind.BatteryExemption to "deviceIdleWhitelist"
            "secureSetting" -> GrantKind.SecureSetting to g.fields.getValue("key")
            "globalSetting" -> GrantKind.GlobalSetting to g.fields.getValue("key")
            else -> throw IllegalArgumentException("unknown grant kind '${g.kind}'")
        }
        return Grant(kind, name, g.optional, g, why = g.why?.let(text::get))
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

    @Volatile private var bundled: LoadedCatalogue? = null

    /** The catalogue shipped inside the app; its serial is the floor for every update. */
    val bundledSerial: Long get() = bundled?.serial ?: 0L

    /**
     * Loads the catalogue unless one is installed already (previews, tests): the stored (downloaded)
     * one when it still verifies and is not older than the bundled one, else the bundled one.
     */
    fun ensure(context: Context) {
        if (loaded == null) adoptStored(context)
    }

    /**
     * Re-resolves the catalogue text when the locales of [context] changed since it was loaded (the
     * system or per-app language changed while the process lived); called when an activity is created.
     */
    fun followLocales(context: Context) {
        val locales = localesOf(context)
        val current = loaded ?: return
        if (current.locales == locales) return
        bundled?.let { bundled = LoadedCatalogue(it.engine, locales) }
        install(LoadedCatalogue(current.engine, locales))
    }

    private fun localesOf(context: Context): List<Locale> =
        context.resources.configuration.locales.let { list -> (0 until list.size()).map(list::get) }

    /** Installs the stored catalogue if valid (see [select]); called at startup and when Home adopts an update. */
    fun adoptStored(context: Context) {
        val app = context.applicationContext
        val locales = localesOf(app)
        val base = bundled ?: loadBundled(app.assets, locales).also { bundled = it }
        install(select(base, StoredCatalogue(File(app.filesDir, DIR)), publicKey(app.assets), BuildConfig.VERSION_CODE, locales))
    }

    private fun publicKey(assets: AssetManager) = assets.open("$DIR/catalogue.pub").use { String(it.readBytes(), Charsets.UTF_8) }

    /**
     * The stored catalogue when it re-verifies with the bundled key, fits this app and is not older
     * than [bundled] (an app update with a newer bundled catalogue wins); otherwise the stored files
     * are deleted and [bundled] is used.
     */
    @VisibleForTesting
    internal fun select(
        bundled: LoadedCatalogue,
        store: StoredCatalogue,
        publicKeyPem: String,
        appVersionCode: Int,
        locales: List<Locale>,
    ): LoadedCatalogue {
        val files = store.read() ?: return bundled
        val stored = try {
            fromBytes(files.json, files.signature, publicKeyPem, appVersionCode, locales)
        } catch (e: RuntimeException) {
            null
        }
        if (stored == null || stored.serial < bundled.serial) {
            store.delete()
            return bundled
        }
        return stored
    }

    private const val DIR = "catalogue"

    /**
     * Reads the three bundled assets and loads them through the verifying parser. A bundled catalogue
     * that does not verify is a broken build, so this throws instead of falling back to anything.
     */
    fun loadBundled(assets: AssetManager, locales: List<Locale>): LoadedCatalogue {
        fun read(name: String) = assets.open("$DIR/$name").use { it.readBytes() }
        return try {
            fromBytes(
                read("catalogue.json"), read("catalogue.json.sig"), String(read("catalogue.pub"), Charsets.UTF_8),
                BuildConfig.VERSION_CODE, locales,
            )
        } catch (e: IllegalStateException) {
            throw IllegalStateException("the bundled catalogue is unusable (broken build): ${e.message}", e)
        }
    }

    /** Verifies [bytes] against [signature] and [publicKeyPem], parses them and checks the app version. */
    @VisibleForTesting
    fun fromBytes(
        bytes: ByteArray,
        signature: ByteArray,
        publicKeyPem: String,
        appVersionCode: Int,
        locales: List<Locale>,
    ): LoadedCatalogue {
        val engine = try {
            Catalogue.parseSigned(bytes, signature, CatalogueVerifier.parsePublicKey(publicKeyPem))
        } catch (e: RuntimeException) {
            throw IllegalStateException("the catalogue failed verification: ${e.message}", e)
        }
        check(engine.minAppVersion <= appVersionCode) {
            "the catalogue needs app version ${engine.minAppVersion}, this is $appVersionCode"
        }
        return LoadedCatalogue(engine, locales)
    }
}
