package io.openshm.patcher.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** The key an APK is signed with. */
class SigningKey(val privateKey: PrivateKey, val certificates: List<X509Certificate>) {
    init {
        require(certificates.isNotEmpty()) { "a signing key needs its certificate" }
    }

    companion object {
        /** Loads the key [alias] (or the only key when null) from a PKCS12 or JKS keystore file. */
        fun fromKeyStore(file: File, storePassword: CharArray, alias: String? = null, keyPassword: CharArray = storePassword, type: String = "PKCS12"): SigningKey {
            val ks = KeyStore.getInstance(type)
            file.inputStream().use { ks.load(it, storePassword) }
            val name = alias ?: ks.aliases().toList().singleOrNull { ks.isKeyEntry(it) }
                ?: throw PatchFailure("${file.name}: give the key alias (the keystore does not have exactly one key)")
            val key = ks.getKey(name, keyPassword) as? PrivateKey
                ?: throw PatchFailure("${file.name}: no private key '$name'")
            val chain = ks.getCertificateChain(name)?.map { it as X509Certificate }
                ?: throw PatchFailure("${file.name}: no certificate for '$name'")
            return SigningKey(key, chain)
        }
    }
}

/** [ApkFiles] over a [ZipReader]. */
internal class ZipApkFiles(private val zip: ZipReader) : ApkFiles {
    override val names: List<String> = zip.entries.filter { !it.isDirectory }.map { it.name }

    private fun entry(name: String) = zip.entry(name) ?: throw PatchFailure("the APK has no entry $name")

    override fun head(name: String, n: Int): ByteArray = zip.open(entry(name)).use { it.readNBytes(n) }

    override fun read(name: String): ByteArray = zip.read(entry(name))

    override fun open(name: String): java.io.InputStream = zip.open(entry(name))
}

/** One step of a run: its name, outcome, time and details. */
class PhaseReport(val name: String, val ok: Boolean, val millis: Long, val details: List<String>)

/** The structured result of [Patcher.analyze] / [Patcher.patch]. Nothing is thrown for expected failures. */
class PatchReport(
    val inputId: String,
    val profile: String,
    val dryRun: Boolean,
) {
    var family: String? = null
    val phases = ArrayList<PhaseReport>()

    /** Per-pattern result of applying the profile. */
    var patterns: List<PatternResult> = emptyList()

    /** Per-pattern result of the applied-mode self-check on the finished APK. */
    var verification: List<PatternResult> = emptyList()
    var ok = false
    var error: String? = null
    var outputSha256: String? = null
    var injectedClasses = 0

    fun text(): String = buildString {
        append("input ").append(inputId).append(", profile ").append(profile)
        family?.let { append(", family ").append(it) }
        append(if (dryRun) " (analysis only)" else "").append('\n')
        for (p in phases) {
            append(if (p.ok) " [ok]   " else " [FAIL] ").append(p.name).append(" (").append(p.millis).append(" ms)\n")
            p.details.forEach { append("          ").append(it).append('\n') }
        }
        for ((title, list) in listOf("patterns" to patterns, "self-check" to verification)) {
            if (list.isEmpty()) continue
            append(title).append(":\n")
            for (r in list) {
                append(if (r.error != null) "  - " else if (r.log.isEmpty()) "  . " else "  + ").append(r.id).append(": ").append(r.description).append('\n')
                r.log.forEach { append("      -> ").append(it).append('\n') }
                r.error?.let { append("      !! ").append(it).append('\n') }
            }
        }
        error?.let { append("FAILED: ").append(it).append('\n') }
    }
}

/** What to patch and with what. */
class PatchRequest(
    /**
     * The APK to patch. It must sit in app-private storage: it is hashed and then reopened by
     * path, so a file another process can write to could be swapped between the two.
     */
    val inputApk: File,
    /** Id of the catalogue input the APK must be (its sha256 is checked). */
    val inputId: String,
    val profile: String,
    /** The package prefix that replaces `shminterop` in our code. */
    val prefix: String,
    /** The extra code the profile injects, by name (exactly the names the profile lists for the family). */
    val injections: Map<String, InjectionSource> = emptyMap(),
    /** Where the signed APK goes ([patch] only). */
    val outputApk: File? = null,
    val signingKey: SigningKey? = null,
    /** A directory for the unsigned intermediate APK. */
    val workDir: File? = null,
    /**
     * Ids of `optional` catalogue patterns to leave out. An unknown or required id, or part of an
     * `optionGroup`, fails the request; every other pattern still has to match.
     */
    val disabledPatterns: Set<String> = emptySet(),
)

fun interface PatchListener {
    /** A phase is starting (`apply`, `sign`, ...) or a pattern finished (`pattern <id>`). */
    fun onEvent(event: String)

    /** A phase finished (ok or failed) with its detail lines and duration. */
    fun onPhase(report: PhaseReport) {}

    /** A pattern finished: [PatternResult.log] holds one entry per patched site. */
    fun onPattern(result: PatternResult) {}

    companion object {
        val NONE = PatchListener { }
    }
}

/**
 * The patch pipeline: check the input, read the APK, apply the profile to the manifest, the
 * resources and the dex files in memory, rebuild what changed, sign, and verify the result.
 *
 * [analyze] stops after applying (nothing is written: the edits live in memory and are dropped).
 * Expected failures (an anchor that no longer matches, a wrong input, a bad signature) end up in
 * the returned [PatchReport]; only programming errors and I/O errors propagate as exceptions.
 */
class Patcher(private val catalogue: Catalogue) {

    fun analyze(req: PatchRequest, listener: PatchListener = PatchListener.NONE): PatchReport =
        run(req, dryRun = true, listener = listener)

    fun patch(req: PatchRequest, listener: PatchListener = PatchListener.NONE): PatchReport {
        requireNotNull(req.outputApk) { "outputApk is required" }
        requireNotNull(req.signingKey) { "signingKey is required" }
        return run(req, dryRun = false, listener = listener)
    }

    private class Stop(message: String) : Exception(message)

    private fun run(req: PatchRequest, dryRun: Boolean, listener: PatchListener): PatchReport {
        val report = PatchReport(req.inputId, req.profile, dryRun)
        fun <T> phase(name: String, body: (MutableList<String>) -> T): T {
            listener.onEvent(name)
            val details = ArrayList<String>()
            val t0 = System.nanoTime()
            val r = try {
                body(details)
            } catch (e: Exception) {
                val handled = e is PatchFailure || e is PatternRequiredException || e is CatalogueException ||
                    e is Stop || e is IOException || e is NoSuchElementException
                if (!handled) throw e
                details.add(e.message ?: e.javaClass.simpleName)
                val failed = PhaseReport(name, false, (System.nanoTime() - t0) / 1_000_000, details)
                report.phases.add(failed)
                report.error = "$name: ${e.message}"
                listener.onPhase(failed)
                throw Stop(report.error!!)
            }
            val done = PhaseReport(name, true, (System.nanoTime() - t0) / 1_000_000, details)
            report.phases.add(done)
            listener.onPhase(done)
            return r
        }
        try {
            val (profile, input) = phase("check-request") {
                PrefixStager.requireValidPrefix(req.prefix)
                catalogue.requireDisablable(req.disabledPatterns)
                catalogue.profile(req.profile) to (catalogue.inputs.firstOrNull { it.id == req.inputId }
                    ?: throw PatchFailure("the catalogue has no input '${req.inputId}'"))
            }
            val runner = catalogue.runner(req.prefix)

            phase("verify-input") { d ->
                val got = sha256(req.inputApk)
                d.add("sha256 $got")
                if (got != input.sha256) throw PatchFailure("${req.inputApk.name}: sha256 $got is not the expected ${input.sha256} for '${input.id}'")
            }

            // The build runs in its own (non-inline) lambda so its frame, which holds the input
            // dex bytes, the dex tree and the rebuilt dex files, is gone before the self-check
            // loads the output: a renamed Samsung Health rewrites nearly every dex, and keeping
            // both copies alive does not fit a phone's heap.
            val build: () -> Built? = { ZipReader(req.inputApk).use { zip ->
                val state = phase("unpack") { d ->
                    val manifestBytes = zip.read("AndroidManifest.xml")
                    val dexEntries = zip.entries.filter { DEX_RE.matches(it.name) }.sortedBy { dexNumber(it.name) }
                    if (dexEntries.isEmpty()) throw PatchFailure("the APK has no classes.dex")
                    d.add("${zip.entries.size} entries, ${dexEntries.size} dex files")
                    Loaded(zip, manifestBytes, dexEntries)
                }
                val arscEntry = zip.entry("resources.arsc")
                var arsc: ArscResourceTable? = null
                val tableProvider = {
                    arsc ?: ArscResourceTable(zip.read(arscEntry ?: throw PatchFailure("the APK has no resources.arsc"))).also { arsc = it }
                }
                val manifest = BinaryManifest(state.manifestBytes) { tableProvider().table }
                val minSdk = manifest.minSdk ?: 21
                val dexBytes = state.dexEntries.map { it.name to zip.read(it) }
                val tree = DexTree(dexBytes, minSdk)

                val allIds = phase("detect-family") { d ->
                    val pkg = manifest.packageName ?: throw PatchFailure("the manifest has no package")
                    val family = catalogue.detectFamily(pkg, tree)
                    report.family = family
                    d.add("package $pkg, family $family, minSdk $minSdk")
                    profile.families[family]
                        ?: throw PatchFailure("profile '${profile.name}' has no patch list for family '$family'")
                }
                val family = report.family!!
                val ids = allIds.filter { it !in req.disabledPatterns }
                val skipped = allIds.filter { it in req.disabledPatterns }
                val wanted = profile.inject[family] ?: emptyList()
                phase("check-injections") { d ->
                    if (wanted.toSet() != req.injections.keys) {
                        throw PatchFailure("profile '${profile.name}'/$family injects $wanted; given: ${req.injections.keys.sorted()}")
                    }
                    d.add("injections: $wanted")
                }

                val originalPkg = manifest.root.attr("package")
                val ctx = PatchContext(tree, { manifest }, { tableProvider() }, filesProvider = { ZipApkFiles(zip) })
                phase("apply") { d ->
                    try {
                        report.patterns = runner.run(ctx, ids) {
                            listener.onEvent("pattern ${it.id}")
                            listener.onPattern(it)
                        }
                    } catch (e: PatternRequiredException) {
                        report.patterns = e.report
                        throw e
                    }
                    d.add("${report.patterns.size} patterns, ${report.patterns.sumOf { it.log.size }} patched sites")
                    if (skipped.isNotEmpty()) d.add("disabled by the user, not applied: ${skipped.joinToString(", ")}")
                }
                if (dryRun) {
                    report.ok = true
                    return@use null
                }

                // aapt2 names the resources.arsc package after the manifest, and the app resolves
                // resources by name against its own package: whichever op renamed the manifest
                // package, the table's package chunk follows it.
                val finalPkg = manifest.root.attr("package")
                if (ctx.arscPackageRename == null && originalPkg != null && finalPkg != null && finalPkg != originalPkg && arscEntry != null) {
                    ArscPackages.encodeName(finalPkg)
                    ctx.arscPackageRename = originalPkg to finalPkg
                }

                val injected = wanted.map { req.injections.getValue(it) }
                val rebuilt = phase("rebuild-dex") { d ->
                    val r = tree.rebuild(injected)
                    report.injectedClasses = r.injectedClassCount
                    d.add("changed: ${r.changed.map { it.entryName }}, injected ${r.injectedClassCount} classes into ${r.injected?.entryName}")
                    r
                }
                val workDir = req.workDir ?: req.outputApk!!.absoluteFile.parentFile
                workDir.mkdirs()
                val unsigned = File.createTempFile("openshm-", "-unsigned.apk", workDir)
                try {
                    phase("write") { d ->
                        val newManifest = if (manifest.modified) manifest.toBytes() else null
                        val newArsc = arsc?.takeIf { it.modified }?.toBytes()
                        val changedDex = rebuilt.changed.associateBy { it.entryName }
                        writeApk(zip, unsigned, newManifest, newArsc, changedDex, rebuilt.injected, replaced = ctx.replacedEntries, arscRename = ctx.arscPackageRename)
                        if (ctx.replacedEntries.isNotEmpty()) d.add("${ctx.replacedEntries.size} other entries rewritten")
                        d.add("manifest ${if (newManifest != null) "rewritten" else "unchanged"}, resources.arsc ${if (newArsc != null) "rewritten" else "unchanged"}")
                        d.add("${unsigned.length()} bytes unsigned")
                    }
                    phase("sign") { d ->
                        val key = req.signingKey!!
                        val out = req.outputApk!!
                        out.absoluteFile.parentFile.mkdirs()
                        out.delete()
                        signApk(unsigned, out, key, minSdk)
                        report.outputSha256 = sha256(out)
                        d.add("signed ${out.name}: v1=${minSdk < 24} v2 v3, sha256 ${report.outputSha256}")
                    }
                } finally {
                    unsigned.delete()
                }
                Built(family, ids, wanted)
            } }
            val built = build() ?: return report
            try {
                selfCheck(req, profile, built.family, built.ids, built.wanted, req.injections, runner, report, ::phase)
            } catch (e: Throwable) {
                // never leave a signed APK behind that failed its own checks
                req.outputApk!!.delete()
                report.outputSha256 = null
                throw e
            }
            report.ok = report.error == null
        } catch (e: Stop) {
            report.ok = false
        }
        return report
    }

    private class Built(val family: String, val ids: List<String>, val wanted: List<String>)

    private class Loaded(val zip: ZipReader, val manifestBytes: ByteArray, val dexEntries: List<ZipEntryInfo>)

    private fun selfCheck(
        req: PatchRequest,
        profile: CatalogueProfile,
        family: String,
        ids: List<String>,
        wanted: List<String>,
        injections: Map<String, InjectionSource>,
        runner: ProfileRunner,
        report: PatchReport,
        phase: (String, (MutableList<String>) -> Unit) -> Unit,
    ) {
        val out = req.outputApk!!
        phase("verify-signature") { d ->
            val result = verifySignature(out, req.signingKey!!)
            val signer = result.signerCertificates.first()
            d.add("v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme}, certificate ${signer.subjectX500Principal.name}")
        }
        ZipReader(out).use { zip ->
            val resources by lazy {
                ArscResourceTable(zip.read(zip.entry("resources.arsc") ?: throw PatchFailure("the APK has no resources.arsc")))
            }
            val manifest = BinaryManifest(zip.read("AndroidManifest.xml")) { resources.table }
            val dexes = zip.entries.filter { DEX_RE.matches(it.name) }.sortedBy { dexNumber(it.name) }
            val tree = DexTree(dexes.map { it.name to zip.read(it) }, manifest.minSdk ?: 21)
            phase("verify-package") { d ->
                val expected = catalogue.outputs.firstOrNull {
                    val from = it.from
                    val china = it.chinaFrom
                    (from is OutputSource.Patched && from.input == req.inputId && from.profile == req.profile) ||
                        (china != null && china.input == req.inputId && china.profile == req.profile)
                }?.let { Catalogue.expand(it.packageName, req.prefix) as String }
                val actual = manifest.packageName
                if (expected == null) {
                    d.add("package $actual (the catalogue has no output for this input and profile to compare with)")
                } else {
                    if (actual != expected) throw PatchFailure("package is '$actual', expected '$expected'")
                    d.add("package $actual")
                }
                // resources are resolved by name against the app's own package
                zip.entry("resources.arsc")?.let { e ->
                    val names = ArscPackages.packageNames(zip.open(e))
                    if (actual !in names) throw PatchFailure("resources.arsc package is ${names.joinToString { "'$it'" }}, expected '$actual'")
                    d.add("resources.arsc package $actual")
                }
            }
            phase("verify-injections") { d ->
                var n = 0
                for (name in wanted) for (rel in injections.getValue(name).classes().keys) {
                    if (tree.classPath(rel.removeSuffix(".smali")) == null) throw PatchFailure("injected class $rel is missing from the APK")
                    n++
                }
                d.add("$n injected classes present")
            }
            phase("verify-anchors") { d ->
                val ctx = PatchContext(
                    CanonicalLabelsTree(tree), { manifest }, { resources },
                    canon = SmaliText::canonLabels,
                    filesProvider = { ZipApkFiles(zip) },
                )
                try {
                    report.verification = runner.run(ctx, ids, verify = true)
                } catch (e: PatternRequiredException) {
                    report.verification = e.report
                    throw e
                }
                d.add("${report.verification.size} patterns re-checked in applied mode")
            }
        }
    }

    companion object {
        internal fun writeApk(
            zip: ZipReader,
            dest: File,
            manifest: ByteArray?,
            arsc: ByteArray?,
            changedDex: Map<String, DexOutput>,
            injected: DexOutput?,
            storeArsc: Boolean = false,
            /** Other entries to replace by name (keeps each original entry's stored/compressed method). */
            replaced: Map<String, ByteArray> = emptyMap(),
            /** Package-chunk rename of `resources.arsc` (old to new), applied to [arsc] or streamed from [zip]. */
            arscRename: Pair<String, String>? = null,
        ) {
            val lastDex = zip.entries.filter { DEX_RE.matches(it.name) }.maxByOrNull { dexNumber(it.name) }
            ZipWriter(BufferedOutputStream(FileOutputStream(dest), 256 * 1024)).use { w ->
                for (e in zip.entries) {
                    if (isSignatureFile(e.name)) continue
                    if (e.name == "resources.arsc" && arsc == null && arscRename != null) {
                        val patches = ArscPackages.renamePatches(zip.open(e), arscRename.first, arscRename.second)
                        if (patches.isNotEmpty()) {
                            w.addPatchedStored(zip, e, patches, 4)
                            continue
                        }
                    }
                    val replacement: ByteArray? = when {
                        e.name == "AndroidManifest.xml" -> manifest
                        e.name == "resources.arsc" -> arsc?.also { if (arscRename != null) ArscPackages.rename(it, arscRename.first, arscRename.second) }
                        else -> changedDex[e.name]?.bytes ?: replaced[e.name]
                    }
                    val stored = e.isStored || (storeArsc && e.name == "resources.arsc" && replacement != null)
                    val align = if (stored) (if (e.name.endsWith(".so")) SO_ALIGN else 4) else 1
                    if (replacement != null) w.addBytes(e.name, replacement, stored, align, e) else w.addRaw(zip, e, align)
                    if (e === lastDex && injected != null) w.addBytes(injected.entryName, injected.bytes, stored = false)
                }
                if (injected != null && lastDex == null) w.addBytes(injected.entryName, injected.bytes, stored = false)
                w.finish()
            }
        }

        /** Signs [unsigned] into [out] (v2 and v3, v1 below API 24); deletes [out] if signing fails. */
        internal fun signApk(unsigned: File, out: File, key: SigningKey, minSdk: Int) {
            val signer = ApkSigner.SignerConfig.Builder("CERT", key.privateKey, key.certificates).build()
            try {
                ApkSigner.Builder(listOf(signer))
                    .setInputApk(unsigned)
                    .setOutputApk(out)
                    .setMinSdkVersion(minSdk)
                    .setV1SigningEnabled(minSdk < 24)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(true)
                    .build()
                    .sign()
            } catch (e: Exception) {
                out.delete() // no half-written output
                throw e
            }
        }

        /** Verifies the signature of [out] and that [key]'s certificate made it; returns the verdict. */
        internal fun verifySignature(out: File, key: SigningKey): ApkVerifier.Result {
            val result = ApkVerifier.Builder(out).build().verify()
            if (!result.isVerified) {
                throw PatchFailure("the signature does not verify: " + (result.errors + result.v1SchemeSigners.flatMap { it.errors } +
                    result.v2SchemeSigners.flatMap { it.errors } + result.v3SchemeSigners.flatMap { it.errors })
                    .joinToString("; ") { it.toString() })
            }
            if (!result.isVerifiedUsingV2Scheme && !result.isVerifiedUsingV3Scheme) throw PatchFailure("the APK is not signed with v2 or v3")
            val signer = result.signerCertificates.firstOrNull()
            if (signer == null || signer != key.certificates[0]) throw PatchFailure("the APK is signed with another certificate")
            return result
        }


        internal val DEX_RE = Regex("^classes(\\d*)\\.dex$")
        private const val SO_ALIGN = 16384

        internal fun dexNumber(name: String): Int =
            DEX_RE.matchEntire(name)!!.groupValues[1].let { if (it.isEmpty()) 1 else it.toInt() }

        internal fun isSignatureFile(name: String): Boolean {
            if (!name.startsWith("META-INF/") || name.indexOf('/', 9) >= 0) return false
            val n = name.substring(9).uppercase()
            return n == "MANIFEST.MF" || n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
