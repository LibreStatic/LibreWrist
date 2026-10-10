package io.openshm.patcher.engine

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.io.IOException

/**
 * Our own injected code in compiled form: one placeholder `classes.dex` (every class in
 * `shminterop/`), disassembled and staged to [prefix] on use, like [StagedInjection] does for
 * smali sources. Refuses a dex with no classes or with classes outside the placeholder package,
 * so an injection can only ever add our own code.
 */
class DexInjection(private val dexBytes: ByteArray, prefix: String) : InjectionSource {
    private val stager = PrefixStager(prefix)

    override fun classes(): Map<String, String> {
        val options = DexTree.baksmaliOptions(READ_API)
        val dex = try {
            DexBackedDexFile(Opcodes.forApi(READ_API), dexBytes)
        } catch (e: RuntimeException) {
            throw PatchFailure("injection: not a valid dex file (${e.message})")
        }
        val files = LinkedHashMap<String, String>()
        for (def in dex.classes) {
            val type = def.type
            if (!type.startsWith("L${PrefixStager.PLACEHOLDER}/") || !type.endsWith(";")) {
                throw PatchFailure("injection: class $type is outside ${PrefixStager.PLACEHOLDER}/")
            }
            files[type.substring(1, type.length - 1) + ".smali"] = DexTree.disassembleClass(options, def)
        }
        if (files.isEmpty()) throw PatchFailure("injection: the dex file has no classes")
        return stager.stage(files, compiled = true)
    }

    private companion object {
        /** Highest API the reader should accept; the host tree assembles at its own level. */
        const val READ_API = 34
    }
}

/** What to build: our own app (a placeholder APK from the app assets) with the real prefix and a signature. */
class OwnAppRequest(
    /**
     * The unsigned placeholder APK (aapt2 + d8 output, package `shminterop...`). It must sit in
     * app-private storage, for the same reason as [PatchRequest.inputApk]: it is opened by path
     * and a file another process can write to could be swapped.
     */
    val placeholderApk: File,
    /** Id of an `ownApp` output of the catalogue; its package (with `${PKG_PREFIX}` expanded) is checked. */
    val outputId: String,
    /** The package prefix that replaces `shminterop`. */
    val prefix: String,
    /**
     * SHA-256 of a signing certificate (64 lowercase hex characters). When set, the single
     * `*.shmbridge.permission.STATUS` permission becomes `signature|knownSigner` with this digest as
     * `knownCerts`, so an app signed with that certificate may use it; the same digest is written to the provider's
     * `PATCHER_CERT` meta-data, which `StatusProvider` checks. Only for the phone bridge.
     */
    val knownSignerCert: String? = null,
    val outputApk: File,
    val signingKey: SigningKey,
    /** A directory for the unsigned intermediate APK; defaults to the output's directory. */
    val workDir: File? = null,
)

/** The structured result of [OwnAppBuilder.build]. Expected failures end up here instead of being thrown. */
class OwnAppReport(val outputId: String) {
    val phases = ArrayList<PhaseReport>()
    var ok = false
    var error: String? = null
    var outputSha256: String? = null

    /** Classes of the built APK (the same as in the placeholder). */
    var classCount = 0

    fun text(): String = buildString {
        append("own app ").append(outputId).append('\n')
        for (p in phases) {
            append(if (p.ok) " [ok]   " else " [FAIL] ").append(p.name).append(" (").append(p.millis).append(" ms)\n")
            p.details.forEach { append("          ").append(it).append('\n') }
        }
        error?.let { append("FAILED: ").append(it).append('\n') }
    }
}

/**
 * Turns a placeholder build of our own app into the real one: renames `shminterop` to the prefix
 * in every dex file (via smali, so classes and string literals follow), in the binary manifest
 * (every string: package, component names, authorities, permissions, actions) and in
 * `resources.arsc` (package name and string values), optionally applies the knownSigner edit,
 * then writes, signs (v2 + v3) and self-checks the APK. A failed signature or self-check deletes
 * the output.
 */
class OwnAppBuilder(private val catalogue: Catalogue) {

    private class Stop(message: String) : Exception(message)

    fun build(req: OwnAppRequest, listener: PatchListener = PatchListener.NONE): OwnAppReport {
        val report = OwnAppReport(req.outputId)
        fun <T> phase(name: String, body: (MutableList<String>) -> T): T {
            listener.onEvent(name)
            val details = ArrayList<String>()
            val t0 = System.nanoTime()
            try {
                val r = body(details)
                report.phases.add(PhaseReport(name, true, (System.nanoTime() - t0) / 1_000_000, details))
                return r
            } catch (e: Exception) {
                val handled = e is PatchFailure || e is CatalogueException || e is Stop || e is IOException || e is NoSuchElementException
                if (!handled) throw e
                details.add(e.message ?: e.javaClass.simpleName)
                report.phases.add(PhaseReport(name, false, (System.nanoTime() - t0) / 1_000_000, details))
                report.error = "$name: ${e.message}"
                throw Stop(report.error!!)
            }
        }
        try {
            val expectedPackage = phase("check-request") {
                PrefixStager.requireValidPrefix(req.prefix)
                val output = catalogue.outputs.firstOrNull { it.id == req.outputId }
                    ?: throw PatchFailure("the catalogue has no output '${req.outputId}'")
                if (output.from !is OutputSource.OwnApp) throw PatchFailure("output '${req.outputId}' is not one of our own apps")
                req.knownSignerCert?.let { requireDigest(it) }
                Catalogue.expand(output.packageName, req.prefix) as String
            }
            val stager = PrefixStager(req.prefix)
            val rename = req.prefix != PrefixStager.PLACEHOLDER

            ZipReader(req.placeholderApk).use { zip ->
                val manifest = phase("unpack") { d ->
                    if (zip.entry("resources.arsc") == null) throw PatchFailure("the APK has no resources.arsc")
                    val m = BinaryManifest(zip.read("AndroidManifest.xml")) { null }
                    d.add("${zip.entries.size} entries, package ${m.packageName}")
                    m
                }
                val arscEntry = zip.entry("resources.arsc")!!
                val minSdk = manifest.minSdk ?: 21
                val dexEntries = zip.entries.filter { Patcher.DEX_RE.matches(it.name) }.sortedBy { Patcher.dexNumber(it.name) }
                var classes = 0
                val newDex = LinkedHashMap<String, DexOutput>()
                phase("stage-dex") { d ->
                    if (dexEntries.isEmpty()) throw PatchFailure("the APK has no classes.dex")
                    for (e in dexEntries) {
                        val (bytes, count) = restageDex(zip.read(e), stager, minSdk)
                        newDex[e.name] = DexOutput(e.name, bytes)
                        classes += count
                    }
                    report.classCount = classes
                    d.add("${dexEntries.size} dex files, $classes classes")
                }
                phase("stage-manifest") { d ->
                    val n = manifest.mapStrings(stager::text)
                    d.add("$n strings renamed")
                    req.knownSignerCert?.let {
                        applyKnownSigner(manifest, it)
                        d.add("STATUS permission is signature|knownSigner and PATCHER_CERT is set for $it")
                    }
                }
                val arsc = ArscResourceTable(zip.read(arscEntry))
                phase("stage-resources") { d ->
                    d.add("${arsc.mapStrings(stager::text)} names/strings renamed")
                }

                val workDir = req.workDir ?: req.outputApk.absoluteFile.parentFile
                workDir.mkdirs()
                val unsigned = File.createTempFile("openshm-", "-unsigned.apk", workDir)
                try {
                    phase("write") { d ->
                        val newManifest = if (manifest.modified) manifest.toBytes() else null
                        // resources.arsc has to be stored (API 30+); rewrite it if it is not
                        val newArsc = if (arsc.modified || !arscEntry.isStored) arsc.toBytes() else null
                        Patcher.writeApk(zip, unsigned, newManifest, newArsc, newDex, null, storeArsc = true)
                        d.add("${unsigned.length()} bytes unsigned")
                    }
                    phase("sign") { d ->
                        req.outputApk.absoluteFile.parentFile.mkdirs()
                        req.outputApk.delete()
                        Patcher.signApk(unsigned, req.outputApk, req.signingKey, minSdk)
                        report.outputSha256 = Patcher.sha256(req.outputApk)
                        d.add("signed ${req.outputApk.name}: v1=${minSdk < 24} v2 v3, sha256 ${report.outputSha256}")
                    }
                } finally {
                    unsigned.delete()
                }
                try {
                    selfCheck(req, expectedPackage, rename, classes, ::phase)
                } catch (e: Throwable) {
                    // never leave a signed APK behind that failed its own checks
                    req.outputApk.delete()
                    report.outputSha256 = null
                    throw e
                }
            }
            report.ok = report.error == null
        } catch (e: Stop) {
            report.ok = false
        }
        return report
    }

    private fun selfCheck(
        req: OwnAppRequest,
        expectedPackage: String,
        rename: Boolean,
        classes: Int,
        phase: (String, (MutableList<String>) -> Unit) -> Unit,
    ) {
        val out = req.outputApk
        phase("verify-signature") { d ->
            val result = Patcher.verifySignature(out, req.signingKey)
            d.add("v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme}")
        }
        ZipReader(out).use { zip ->
            val manifest = BinaryManifest(zip.read("AndroidManifest.xml")) { null }
            val arscEntry = zip.entry("resources.arsc") ?: throw PatchFailure("the APK has no resources.arsc")
            phase("verify-package") { d ->
                val actual = manifest.packageName
                if (actual != expectedPackage) throw PatchFailure("package is '$actual', expected '$expectedPackage'")
                if (!arscEntry.isStored) throw PatchFailure("resources.arsc is not stored")
                val arsc = ArscResourceTable(zip.read(arscEntry))
                val packages = arsc.packageNames()
                if (packages.any { it != expectedPackage }) throw PatchFailure("resources.arsc package is $packages, expected '$expectedPackage'")
                d.add("package $actual")
                if (rename) {
                    val left = manifest.poolStrings().filter { PrefixStager.PLACEHOLDER in it } +
                        arsc.poolStrings().filter { PrefixStager.PLACEHOLDER in it }
                    if (left.isNotEmpty()) throw PatchFailure("'${PrefixStager.PLACEHOLDER}' is left in the manifest or resources: ${left.take(3)}")
                }
            }
            phase("verify-dex") { d ->
                val needle = PrefixStager.PLACEHOLDER.toByteArray(Charsets.US_ASCII)
                var n = 0
                for (e in zip.entries.filter { Patcher.DEX_RE.matches(it.name) }) {
                    val bytes = zip.read(e)
                    if (rename && indexOf(bytes, needle) >= 0) throw PatchFailure("'${PrefixStager.PLACEHOLDER}' is left in ${e.name}")
                    n += DexBackedDexFile(Opcodes.forApi(34), bytes).classes.size
                }
                if (n != classes) throw PatchFailure("the APK has $n classes, expected $classes")
                d.add("$n classes")
            }
        }
    }

    companion object {
        private val DIGEST_RE = Regex("^[0-9a-f]{64}$")
        private const val STATUS_SUFFIX = ".shmbridge.permission.STATUS"
        private const val PATCHER_CERT_SUFFIX = ".shmbridge.PATCHER_CERT"

        private fun requireDigest(digest: String) {
            if (!DIGEST_RE.matches(digest)) throw PatchFailure("knownSigner certificate must be 64 lowercase hex characters")
        }

        /**
         * `signature` -> `signature|knownSigner` plus `android:knownCerts` on the single permission
         * named `*.shmbridge.permission.STATUS`; anything but exactly one match fails. The same digest
         * goes into the `android:value` of the single `*.shmbridge.PATCHER_CERT` `<meta-data>` of the
         * provider, which is what `StatusProvider` reads (the knownCerts getter is a hidden API).
         */
        internal fun applyKnownSigner(manifest: BinaryManifest, digest: String) {
            requireDigest(digest)
            val hits = manifest.root.children.filter {
                it.tag == "permission" && it.attr("android:name")?.endsWith(STATUS_SUFFIX) == true &&
                    it.attr("android:protectionLevel") == "signature"
            }
            if (hits.size != 1) throw PatchFailure("expected exactly one signature permission *$STATUS_SUFFIX, found ${hits.size}")
            val metas = manifest.root.children.filter { it.tag == "application" }
                .flatMap { it.children }.filter { it.tag == "provider" }
                .flatMap { it.children }.filter {
                    it.tag == "meta-data" && it.attr("android:name")?.endsWith(PATCHER_CERT_SUFFIX) == true
                }
            if (metas.size != 1) throw PatchFailure("expected exactly one provider <meta-data> *$PATCHER_CERT_SUFFIX, found ${metas.size}")
            metas[0].setAttr("android:value", digest)
            hits[0].setAttr("android:protectionLevel", "signature|knownSigner")
            hits[0].setAttr("android:knownCerts", digest)
        }

        /** Restages one dex: classes without the placeholder are copied as they are, the rest go through smali. */
        private fun restageDex(bytes: ByteArray, stager: PrefixStager, api: Int): Pair<ByteArray, Int> {
            val opcodes = Opcodes.forApi(api)
            val dex = DexBackedDexFile(opcodes, bytes)
            val options = DexTree.baksmaliOptions(api)
            val pool = DexPool(opcodes)
            val builder = DexBuilder(opcodes)
            var n = 0
            for (def in dex.classes) {
                val text = DexTree.disassembleClass(options, def)
                if (PrefixStager.PLACEHOLDER in text) {
                    val rel = def.type.substring(1, def.type.length - 1) + ".smali"
                    pool.internClass(DexTree.assembleClass(builder, stager.path(rel), stager.smali(text), api))
                } else {
                    pool.internClass(def)
                }
                n++
            }
            val store = MemoryDataStore()
            pool.writeTo(store)
            return store.data to n
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
