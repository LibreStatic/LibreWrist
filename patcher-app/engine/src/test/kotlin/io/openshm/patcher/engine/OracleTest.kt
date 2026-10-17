package io.openshm.patcher.engine

import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Equivalence oracle: the Kotlin engine against the Python pipeline (apktool + superpatcher.py).
 *
 * Opt-in: runs only when SHM_ORACLE_APKS points at a directory with the official APKs
 * (`alarm-watch-1.3.05.5.apk`, `shm-watch-1.5.1.022.apk`, `shm-phone-1.4.6.001.apk`,
 * `shealth-7.00.6.011.apk`, and `shealth-7.00.6.012-cn.apk` for the `shealth-cn` subset, the China build). It needs
 * the tools of the scripts (python3, bash, apktool, Android build tools, javac), takes minutes and
 * several GB of memory (apktool runs one at a time, 4 GB heap). Variables:
 *
 *   SHM_ORACLE_APKS   directory with the official APKs (also gives APKS_DIR to the scripts)
 *   SHM_ORACLE_WORK   scratch directory (default ~/.cache/shm-interop/oracle-kotlin)
 *   SHM_ORACLE_ONLY   comma separated subset of: alarm, watch, phone, shealth, shealth-cn, bridge-watch, bridge-phone
 *   SHM_TEST_HEAP     heap of the test JVM (default 3g); 512m checks that the Kotlin engine fits a phone
 *   SHM_ORACLE_OWNCODE  opt-in Phase 2: the output directory of the app's `ownCodeAssets` Gradle task
 *                     (contains `owncode/` or is that directory: the `injections` and `apps` directories).
 *                     The alarm and watch comparisons then inject `DexInjection(<asset dex>)` on the
 *                     Kotlin side instead of the scripts' smali (the Python side keeps the scripts'
 *                     smali), and the subsets bridge-watch / bridge-phone run: `build-bridge.sh` against
 *                     `OwnAppBuilder` on the placeholder APK (phone with a fixed test knownSigner digest),
 *                     decoded trees compared like the others (dex bytes are never compared, only their
 *                     decoded smali, and for these two subsets smali is compared loosely, see [looseSmali]). The scripts run with JDK 17 first on PATH (SHM_ORACLE_JDK, default
 *                     /usr/lib/jvm/java-17-openjdk) because the assets are built with it. Without this
 *                     variable the bridge subsets are skipped.
 *   SHM_ORACLE_KEEP   keep the scratch directory
 *   APKTOOL_JAR       apktool jar (default: third_party/apktool_3.0.3.jar of the repo or of the
 *                     directory above SHM_ORACLE_APKS)
 *
 * For every APK, with PKG_PREFIX=com.example: the injection smali is built once by the scripts'
 * own tooling and fed to both sides; the Python side produces the unsigned APK, the Kotlin side
 * the signed one; both are decoded with apktool and the decoded trees are compared (smali text
 * exactly, XML after normalisation). Signatures, the binary encoding and `original/` are not
 * compared. The test fails with the list of differences.
 *
 * Normalisation, deliberately: XML attributes are sorted; namespace declarations are compared by URI
 * and prefixed names carry the URI instead of the prefix (apktool names the `res-auto` prefix `ns1`,
 * ARSCLib keeps `app`); the children of resource value files are
 * compared as sets (ARSCLib and apktool order entries differently); `public.xml` ids are ignored
 * (the Python rebuild renumbers resources, the Kotlin engine keeps the input's ids, so the Kotlin
 * output is the faithful one); in apktool.yml the file name, `doNotCompress` and `packageName` are
 * ignored. Everything else (every smali file, every other decoded file) must be identical.
 */
class OracleTest {
    private val apksDir: String? = System.getenv("SHM_ORACLE_APKS")
    private val repo = File(System.getProperty("shm.repoRoot"))
    private val prefix = System.getenv("SHM_ORACLE_PREFIX") ?: "com.example"
    private val ownCode: File? = System.getenv("SHM_ORACLE_OWNCODE")?.let { d ->
        File(d).let { if (File(it, "owncode").isDirectory) File(it, "owncode") else it }
    }
    private val testDigest = "0123456789abcdef".repeat(4)

    /**
     * [relaxed]: differences that only come from how each side rewrites what the patches do not
     * touch are dropped — aapt2 re-crunches PNGs in the Python build, and dexlib2 writes the static
     * default values (`= false`/`null`/`0x0`) and splits try ranges around non-throwing
     * instructions of the unpatched classes of a rebuilt dex. Instructions are still compared in order.
     */
    private class Target(
        val name: String, val apk: String, val inputId: String, val profile: String, val inject: String?,
        val relaxed: Boolean = false,
    )

    private val targets = listOf(
        Target("alarm", "alarm-watch-1.3.05.5.apk", "alarm-watch", "alarm", "alarm-fork"),
        Target("watch", "shm-watch-1.5.1.022.apk", "shm-watch", "gms", "watch-mod"),
        Target("phone", "shm-phone-1.4.6.001.apk", "shm-phone", "gms", null),
        Target("shealth", "shealth-7.00.6.011.apk", "shealth-phone", "shealth", null, relaxed = true),
        Target("shealth-cn", "shealth-7.00.6.012-cn.apk", "shealth-phone-cn", "shealth-cn", null, relaxed = true),
        Target("bridge-watch", "", "bridge-watch", "", null),
        Target("bridge-phone", "", "bridge-phone", "", null),
    )

    private fun sh(dir: File, env: Map<String, String>, log: File, vararg cmd: String) {
        val pb = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).redirectOutput(log)
        pb.environment().putAll(env)
        val p = pb.start()
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            throw AssertionError("timeout: ${cmd.joinToString(" ")}")
        }
        if (p.exitValue() != 0) {
            throw AssertionError("exit ${p.exitValue()}: ${cmd.joinToString(" ")}\n" + log.readLines().takeLast(25).joinToString("\n"))
        }
    }

    @Test fun kotlinEngineEqualsPythonPipeline() {
        Assume.assumeTrue("SHM_ORACLE_APKS is not set", apksDir != null)
        val only = System.getenv("SHM_ORACLE_ONLY")?.split(',')?.map { it.trim() }
        val work = File(System.getenv("SHM_ORACLE_WORK") ?: (System.getProperty("user.home") + "/.cache/shm-interop/oracle-kotlin"))
        work.deleteRecursively()
        work.mkdirs()
        val apktoolJar = System.getenv("APKTOOL_JAR")
            ?: listOf(File(repo, "third_party/apktool_3.0.3.jar"), File(File(apksDir!!).absoluteFile.parentFile, "third_party/apktool_3.0.3.jar"))
                .firstOrNull { it.exists() }?.path
            ?: throw AssertionError("apktool jar not found; set APKTOOL_JAR")
        val jdk = System.getenv("SHM_ORACLE_JDK") ?: "/usr/lib/jvm/java-17-openjdk"
        val env = mapOf(
            "PATH" to "$jdk/bin:" + System.getenv("PATH"),
            "PKG_PREFIX" to prefix,
            "APKS_DIR" to apksDir!!,
            "APKTOOL_JAR" to apktoolJar,
            "WORK_DIR" to File(work, "scripts-work").path,
        )
        val keystore = File(work, "oracle.p12")
        sh(work, emptyMap(), File(work, "keytool.log"),
            File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-alias", "oracle", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "30", "-dname", "CN=oracle", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
        val key = SigningKey.fromKeyStore(keystore, "changeit".toCharArray())
        val catalogue = Catalogue.parse(File(repo, "catalogue/catalogue.json").readText())

        val report = StringBuilder()
        val failures = ArrayList<String>()
        try {
            for (t in targets) {
                if (only != null && t.name !in only) continue
                if (t.name.startsWith("bridge-") && ownCode == null) continue
                val dir = File(work, t.name).also { it.mkdirs() }
                val summary = if (t.name.startsWith("bridge-")) runBridge(t, dir, env, key, catalogue, failures)
                else runTarget(t, dir, env, key, catalogue, failures)
                report.append(summary).append('\n')
                println(summary)
            }
        } finally {
            File(work, "oracle-report.txt").writeText(report.toString() + failures.joinToString("\n") + "\n")
            if (System.getenv("SHM_ORACLE_KEEP") == null && failures.isEmpty()) work.deleteRecursively()
        }
        assertTrue("equivalence differences:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun runTarget(t: Target, dir: File, env: Map<String, String>, key: SigningKey, catalogue: Catalogue, failures: MutableList<String>): String {
        val apk = File(apksDir!!, t.apk)
        val injectDir = File(dir, "inject")
        val injections = HashMap<String, InjectionSource>()
        val pyArgs = ArrayList<String>()
        when (t.inject) {
            "alarm-fork" -> sh(repo, env, File(dir, "inject.log"), "bash", "-c",
                "source \"\$0/scripts/lib/common.sh\"; require_tools; java_to_smali \"\$0/shm-bridge/alarm-fork/src\" \"\$1\" 11 34", repo.path, injectDir.path)
            "watch-mod" -> sh(repo, env, File(dir, "inject.log"), "bash", File(repo, "watch-mod/build-watch-mod.sh").path, injectDir.path)
        }
        if (t.inject != null) {
            injections[t.inject] = ownCode?.let { DexInjection(File(it, "injections/${t.inject}.dex").readBytes(), prefix) }
                ?: DirectoryInjection(injectDir)
            pyArgs += listOf("--inject", "${t.inject}=${injectDir.path}")
        }

        // Python pipeline
        val pyOut = File(dir, "py").also { it.mkdirs() }
        sh(repo, env, File(dir, "python.log"), "python3", File(repo, "scripts/superpatcher.py").path, "patch", apk.path,
            "--profile", t.profile, "-o", pyOut.path, "--name", t.name, *pyArgs.toTypedArray())
        val pyApk = File(pyOut, "${t.name}-unsigned.apk")

        // Kotlin engine
        val ktApk = File(dir, "kotlin-${t.name}.apk")
        val t0 = System.nanoTime()
        val report = Patcher(catalogue).patch(PatchRequest(apk, t.inputId, t.profile, prefix, injections, ktApk, key, dir))
        val ms = (System.nanoTime() - t0) / 1_000_000
        File(dir, "kotlin-report.txt").writeText(report.text())
        if (!report.ok) {
            failures.add("${t.name}: the Kotlin engine failed: ${report.error}")
            return "${t.name}: Kotlin engine FAILED after $ms ms: ${report.error}"
        }

        // renameFile only matters to apktool/aapt2: the Kotlin engine keeps the stored names
        val renamed = catalogue.patterns.values.flatMap { it.steps }.filter { it["op"] == "renameFile" }.associate {
            val path = it["path"] as String
            path.substringBeforeLast('/') + "/" + it["name"] as String to path
        }
        return compareApks(t.name, pyApk, ktApk, dir, env, failures, ms, renamed, t.relaxed)
    }

    /** Phase 2: the scripts' bridge build against [OwnAppBuilder] on the placeholder asset. */
    private fun runBridge(t: Target, dir: File, env: Map<String, String>, key: SigningKey, catalogue: Catalogue, failures: MutableList<String>): String {
        val which = t.name.removePrefix("bridge-")
        val dist = File(dir, "dist").also { it.mkdirs() }
        val bridgeEnv = HashMap(env)
        bridgeEnv["BRIDGE_ONLY"] = which
        bridgeEnv["DIST_DIR"] = dist.path
        if (which == "phone") bridgeEnv["PATCHER_CERT_SHA256"] = testDigest
        sh(repo, bridgeEnv, File(dir, "bridge-script.log"), "bash", File(repo, "shm-bridge/scripts/build-bridge.sh").path)
        val pyApk = File(dist, "shm-bridge-$which.apk")

        val ktApk = File(dir, "kotlin-${t.name}.apk")
        val t0 = System.nanoTime()
        val placeholder = File(dir, "placeholder.apk")
        File(ownCode!!, "apps/${t.name}.apk").copyTo(placeholder, overwrite = true)
        val report = OwnAppBuilder(catalogue).build(OwnAppRequest(placeholder, t.name, prefix,
            if (which == "phone") testDigest else null, ktApk, key, dir))
        val ms = (System.nanoTime() - t0) / 1_000_000
        File(dir, "kotlin-report.txt").writeText(report.text())
        if (!report.ok) {
            failures.add("${t.name}: OwnAppBuilder failed: ${report.error}")
            return "${t.name}: OwnAppBuilder FAILED after $ms ms: ${report.error}"
        }
        // renameFile only matters to apktool/aapt2: the Kotlin engine keeps the stored names
        val renamed = catalogue.patterns.values.flatMap { it.steps }.filter { it["op"] == "renameFile" }.associate {
            val path = it["path"] as String
            path.substringBeforeLast('/') + "/" + it["name"] as String to path
        }
        return compareApks(t.name, pyApk, ktApk, dir, env, failures, ms, renamed, t.relaxed)
    }

    private fun compareApks(
        name: String, pyApk: File, ktApk: File, dir: File, env: Map<String, String>, failures: MutableList<String>, ms: Long,
        renamed: Map<String, String> = emptyMap(), relaxed: Boolean = false,
    ): String {
        val pyDec = File(dir, "dec-py")
        val ktDec = File(dir, "dec-kotlin")
        for ((a, d) in listOf(pyApk to pyDec, ktApk to ktDec)) {
            sh(repo, env, File(dir, "apktool-${d.name}.log"), "bash", "-c",
                "source \"\$0/scripts/lib/common.sh\"; apktool d -f \"\$1\" -o \"\$2\"", repo.path, a.path, d.path)
        }
        val diffs = compareTrees(pyDec, ktDec, loose = name.startsWith("bridge-"), renamed = renamed, relaxed = relaxed).toMutableList()
        // The decoded trees do not show the package-chunk name of resources.arsc; compare it directly (streamed).
        val arscPackages = listOf(pyApk, ktApk).map { apk ->
            ZipReader(apk).use { z -> z.entry("resources.arsc")?.let { ArscPackages.packageNames(z.open(it)) } }
        }
        if (arscPackages[0] != arscPackages[1]) diffs.add("resources.arsc package names differ: python ${arscPackages[0]}, kotlin ${arscPackages[1]}")
        File(dir, "diffs.txt").writeText(diffs.joinToString("\n"))
        diffs.forEach { failures.add("$name: $it") }
        return "$name: Kotlin ${ms} ms, ${diffs.size} difference(s)"
    }

    // ------------------------------------------------------------------ tree comparison

    /** [renamed] maps a file name of the Python tree back to the name the Kotlin engine keeps. */
    private fun compareTrees(
        py: File, kt: File, loose: Boolean = false, renamed: Map<String, String> = emptyMap(), relaxed: Boolean = false,
    ): List<String> {
        val a = files(py).mapKeys { (p, _) -> renamed[p] ?: p }
        val b = files(kt)
        val diffs = ArrayList<String>()
        if (loose) {
            val (la, lb) = looseSmali(a) to looseSmali(b)
            for (p in la.keys - lb.keys) diffs.add("only in Python output (loose smali): $p")
            for (p in lb.keys - la.keys) diffs.add("only in Kotlin output (loose smali): $p")
            for (p in (la.keys intersect lb.keys).sorted()) compareLines(la.getValue(p), lb.getValue(p))?.let { diffs.add("$p (loose): $it") }
        }
        for (p in a.keys - b.keys) diffs.add("only in Python output: $p")
        for (p in b.keys - a.keys) diffs.add("only in Kotlin output: $p")
        for (p in (a.keys intersect b.keys).sorted()) {
            val fa = a.getValue(p)
            val fb = b.getValue(p)
            val d = when {
                loose && p.endsWith(".smali") -> null
                p.endsWith(".xml") -> compareXml(p, fa, fb)
                p == "apktool.yml" -> compareLines(yml(fa), yml(fb))
                relaxed && p.endsWith(".png") -> null
                relaxed && p.endsWith(".smali") -> compareLines(relaxedSmali(fa), relaxedSmali(fb))
                p.endsWith(".smali") || p.endsWith(".txt") || p.endsWith(".json") || p.endsWith(".properties") -> compareLines(fa.readLines(), fb.readLines())
                else -> if (fa.length() != fb.length() || !fa.readBytes().contentEquals(fb.readBytes())) "binary file differs (${fa.length()} vs ${fb.length()} bytes)" else null
            }
            if (d != null) diffs.add("$p: $d")
        }
        return diffs
    }

    /**
     * Smali of a bridge APK, compared as bags of instructions per class. The Python side is d8 on
     * Java sources with the real prefix; the Kotlin side is d8 on the placeholder, renamed and
     * reassembled through smali. What differs for that reason alone is dropped: labels and `.line` /
     * `.catch` (the assembler merges adjacent try ranges and renumbers labels), the order of
     * instructions, `goto` widths and `nop` padding (assembler), the content hashes of `$r8$lambda$...` and the numbering of
     * `ExternalSyntheticLambdaN` (d8 derives both from the class names; the lambda classes of one
     * package are pooled). String-switch keys stay in (sorted), so a stale hash is still a difference.
     */
    private fun looseSmali(files: Map<String, File>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for ((p, f) in files) {
            if (!p.endsWith(".smali")) continue
            val key = p.replace(Regex("ExternalSyntheticLambda\\d+"), "ExternalSyntheticLambda*")
            val lines = out.getOrPut(key) { ArrayList() }
            for (l in f.readLines()) {
                val t = l.trim()
                if (t.isEmpty() || t.startsWith(":") || t.startsWith(".line") || t.startsWith(".catch") || t == "nop" || t.startsWith("#")) continue
                lines.add(
                    t.replace(Regex("^goto/(16|32) "), "goto ")
                        .replace(Regex(":(goto|cond|try_start|try_end|catch|pswitch|sswitch|array)\\w*"), ":L")
                        .replace(Regex("\\\$r8\\\$lambda\\\$[\\w-]+"), "\\\$r8\\\$lambda\\\$H")
                        .replace(Regex("ExternalSyntheticLambda\\d+"), "ExternalSyntheticLambdaN"),
                )
            }
        }
        out.values.forEach { it.sort() }
        return out
    }

    private fun relaxedSmali(f: File): List<String> = f.readLines().mapNotNull { l ->
        val t = l.trim()
        when {
            t.startsWith(":try_") || t.startsWith(".catch ") || t.startsWith(".catchall ") -> null
            t.startsWith(".field ") -> t.replace(Regex(" = (false|null|0x0)$"), "")
            else -> l
        }
    }

    /** Relative path -> file, `original/` (the packaged binaries and signatures) excluded. */
    private fun files(root: File): Map<String, File> =
        root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .filter { !it.first.startsWith("original/") }
            .toMap()

    private fun compareLines(a: List<String>, b: List<String>): String? {
        if (a == b) return null
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        val sa = a.drop(i).take(3).joinToString(" | ")
        val sb = b.drop(i).take(3).joinToString(" | ")
        return "differs at line ${i + 1} (python ${a.size} lines, kotlin ${b.size}): python=[$sa] kotlin=[$sb]"
    }

    /**
     * apktool.yml without what legitimately differs: the file name, and the `doNotCompress` and
     * `packageName` entries (the Python rebuild is apktool's, the Kotlin one keeps the input's zip layout).
     */
    private fun yml(f: File): List<String> {
        val out = ArrayList<String>()
        var skipList = false
        for (l in f.readLines()) {
            if (skipList && l.startsWith("- ")) continue
            skipList = l.startsWith("doNotCompress:")
            if (skipList || l.startsWith("apkFileName:") || l.trim().startsWith("packageName:")) continue
            out.add(l)
        }
        return out
    }

    /**
     * Canonical lines of an XML file. [sortChildren]: the children of the root are an unordered set
     * (resource value files; ARSCLib and apktool order entries differently), so their blocks are sorted.
     * [dropIds]: ignore `id` attributes (public.xml: apktool renumbers entries on its rebuild, the Kotlin
     * engine keeps the input's ids; reported as a known difference, not compared).
     */
    private fun canonical(f: File, sortChildren: Boolean, dropIds: Boolean): List<String> {
        val dbf = DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = false
        val doc = dbf.newDocumentBuilder().parse(f)
        val out = ArrayList<String>()
        val rootAttrs = doc.documentElement.attributes
        val nsByPrefix = (0 until rootAttrs.length).map { rootAttrs.item(it) }
            .filter { it.nodeName.startsWith("xmlns:") }.associate { it.nodeName.removePrefix("xmlns:") to it.nodeValue }
        // A prefixed name is rendered with its namespace URI, so the prefix's spelling does not matter.
        fun qn(name: String): String {
            val i = name.indexOf(':')
            return if (i < 0) name else nsByPrefix[name.substring(0, i)]?.let { "{$it}" + name.substring(i + 1) } ?: name
        }
        fun walk(n: Node, depth: Int) {
            when (n) {
                is Element -> {
                    val attrs = (0 until n.attributes.length).map { n.attributes.item(it) }
                        .filterNot { dropIds && it.nodeName == "id" }
                        .map { if (it.nodeName.startsWith("xmlns:")) "xmlns:*" to it.nodeValue else qn(it.nodeName) to it.nodeValue }
                        .sortedWith(compareBy({ it.first }, { it.second })).joinToString(" ") { "${it.first}=\"${it.second}\"" }
                    out.add("  ".repeat(depth) + "<" + qn(n.tagName) + (if (attrs.isNotEmpty()) " $attrs" else "") + ">")
                    if (!sortChildren) {
                        for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i), depth + 1)
                    } else {
                        // value files: <enum>/<flag>/<item> children are sets too
                        val mark = out.size
                        val blocks = ArrayList<String>()
                        for (i in 0 until n.childNodes.length) {
                            val before = out.size
                            walk(n.childNodes.item(i), depth + 1)
                            if (out.size > before) {
                                blocks.add(out.subList(before, out.size).joinToString("\n"))
                                while (out.size > before) out.removeAt(out.size - 1)
                            }
                        }
                        blocks.sort()
                        assert(out.size == mark + 0)
                        blocks.forEach { b -> out.addAll(b.split("\n")) }
                    }
                }
                is org.w3c.dom.Text -> if (n.data.isNotBlank()) out.add("  ".repeat(depth) + n.data.trim())
            }
        }
        if (!sortChildren) {
            walk(doc.documentElement, 0)
            return out
        }
        val root = doc.documentElement
        val blocks = ArrayList<String>()
        for (i in 0 until root.childNodes.length) {
            out.clear()
            walk(root.childNodes.item(i), 1)
            if (out.isNotEmpty()) blocks.add(out.joinToString("\n"))
        }
        blocks.sort()
        return listOf("<${root.tagName}>") + blocks.flatMap { it.split("\n") }
    }

    private fun compareXml(path: String, a: File, b: File): String? {
        val values = path.startsWith("res/values")
        val ids = path.endsWith("/public.xml")
        val ca = try { canonical(a, values, ids) } catch (e: Exception) { return if (a.readBytes().contentEquals(b.readBytes())) null else "not parseable XML and the bytes differ" }
        val cb = try { canonical(b, values, ids) } catch (e: Exception) { return "kotlin side does not parse: ${e.message}" }
        return compareLines(ca, cb)
    }
}
