package io.openshm.patcher.engine

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** End to end over a synthetic APK built here (no vendor files): zip, dex, manifest, signing. */
class PatcherTest {
    private lateinit var dir: File
    private val api = 21

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "patcher-test-" + System.nanoTime()).also { it.mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private val target = """
.class public Lcom/example/syn/Main;
.super Ljava/lang/Object;

.method public static greeting()Ljava/lang/String;
    .locals 1

    const-string v0, "hello"

    return-object v0
.end method

.method public static farewell()Ljava/lang/String;
    .locals 1

    const-string v0, "bye"

    return-object v0
.end method

.method public static italian()Ljava/lang/String;
    .locals 1

    const-string v0, "ciao"

    return-object v0
.end method

.method public static spanish()Ljava/lang/String;
    .locals 1

    const-string v0, "adios"

    return-object v0
.end method
"""

    private fun syntheticApk(file: File, pkg: String = "com.example.syn", withDex: Boolean = true) {
        val manifest = AndroidManifestBlock.empty().also {
            it.packageName = pkg
            it.minSdkVersion = 21
            it.getOrCreateApplicationElement()
            it.refresh()
        }.bytes
        val dex = DexTree.buildDex(mapOf("com/example/syn/Main.smali" to target), api)
        ZipWriter(FileOutputStream(file)).use { z ->
            z.addBytes("AndroidManifest.xml", manifest, stored = false)
            if (withDex) z.addBytes("classes.dex", dex, stored = false)
            z.addBytes("assets/readme.txt", "plain".toByteArray(), stored = true)
            z.finish()
        }
    }

    /** An optional pattern on the string constant [word]; [group] puts it in an option group. */
    private fun optionalPattern(word: String, group: String? = null) =
        """{"families": ["syn"], "title": "t", "description": "d", "optional": true,
    ${if (group != null) "\"optionGroup\": \"$group\"," else ""}
    "ops": [{"op": "replaceInClass", "className": "com/example/syn/Main", "old": "\"$word\"", "new": "\"$word!\""}]}"""

    private fun catalogue(
        apk: File,
        patchNew: String = "\\\"patched\\\"",
        old: String = "\\\"hello\\\"",
        withOptional: Boolean = false,
        groupOfSpanish: String? = "g",
    ): Catalogue {
        val extraIds = if (withOptional) ", \"wave\", \"ga\", \"gb\"" else ""
        val extraPatterns = if (withOptional) {
            ", \"wave\": ${optionalPattern("bye")}, \"ga\": ${optionalPattern("ciao", "g")}, \"gb\": ${optionalPattern("adios", groupOfSpanish)}"
        } else ""
        val text = """
{
  "schema": 1, "serial": 1, "minAppVersion": 1,
  "inputs": [{"id": "syn", "file": "syn.apk", "sha256": "${sha(apk)}", "label": "Synthetic", "package": "com.example.syn",
    "versionName": "1", "versionCode": 1, "minSdk": 21, "targetSdk": 21, "abis": [], "dpi": "nodpi", "format": "apk",
    "target": "phone", "role": "patched"}],
  "families": [{"id": "syn", "package": "com.example.syn"}],
  "profiles": {"p": {"description": "synthetic", "families": {"syn": ["greet"$extraIds]},
    "inject": {"syn": ["extra"]}}},
  "patterns": {"greet": {"families": ["syn"], "title": "Greeting", "description": "changes the greeting", "optional": false,
    "ops": [{"op": "replaceInClass", "className": "com/example/syn/Main", "old": "$old", "new": "$patchNew"}]}$extraPatterns},
  "outputs": [{"id": "syn-out", "device": "phone", "package": "com.example.syn", "from": {"input": "syn", "profile": "p"}}],
  "installOrder": [{"output": "syn-out"}], "grants": {"syn-out": []}, "stockAppsToDisable": []
}"""
        return Catalogue.parse(text)
    }

    private fun request(apk: File, disabled: Set<String>) = PatchRequest(
        apk, "syn", "p", "org.test", mapOf("extra" to StagedInjection(extra, "org.test")), null, null, dir,
        disabledPatterns = disabled,
    )

    private fun applied(report: PatchReport) = report.patterns.map { it.id }

    @Test fun disabledOptionalPatternsAreNotApplied() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val all = Patcher(catalogue(apk, withOptional = true)).analyze(request(apk, emptySet()))
        assertTrue(all.text(), all.ok)
        assertEquals(listOf("greet", "wave", "ga", "gb"), applied(all))

        val some = Patcher(catalogue(apk, withOptional = true)).analyze(request(apk, setOf("wave", "ga", "gb")))
        assertTrue(some.text(), some.ok)
        assertEquals(listOf("greet"), applied(some))

        val one = Patcher(catalogue(apk, withOptional = true)).analyze(request(apk, setOf("wave")))
        assertEquals(listOf("greet", "ga", "gb"), applied(one))
    }

    @Test fun disabledPatternsAreAbsentFromTheFinishedApk() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val out = File(dir, "out.apk")
        val req = PatchRequest(apk, "syn", "p", "org.test", mapOf("extra" to StagedInjection(extra, "org.test")), out, key(), dir,
            disabledPatterns = setOf("wave"))
        val report = Patcher(catalogue(apk, withOptional = true)).patch(req)
        assertTrue(report.text(), report.ok)
        assertEquals(listOf("greet", "ga", "gb"), report.verification.map { it.id })
        ZipReader(out).use { z ->
            val tree = DexTree(listOf("classes.dex" to z.read("classes.dex"), "classes2.dex" to z.read("classes2.dex")), api)
            val main = tree.read(tree.classPath("com/example/syn/Main")!!)
            assertTrue(main.contains("\"bye\""))
            assertTrue(main.contains("\"ciao!\""))
        }
    }

    @Test fun skippingKeepsTheAnchorRuleForTheRest() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val report = Patcher(catalogue(apk, old = "\\\"absent\\\"", withOptional = true)).analyze(request(apk, setOf("wave")))
        assertFalse(report.ok)
        assertTrue(report.error, report.error!!.startsWith("apply"))
    }

    @Test fun requiredUnknownAndPartialGroupSkipsAreRejectedBeforeReadingTheApk() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val cases = mapOf(
            setOf("greet") to "required",
            setOf("nope") to "unknown",
            setOf("ga") to "as a whole",
            setOf("wave", "greet") to "required",
        )
        for ((disabled, text) in cases) {
            val report = Patcher(catalogue(apk, withOptional = true)).analyze(request(apk, disabled))
            assertFalse("$disabled", report.ok)
            assertTrue(report.error, report.error!!.contains(text))
            assertEquals(listOf("check-request"), report.phases.map { it.name })
        }
    }

    @Test fun optionGroupMustHaveTwoOptionalMembers() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        for (group in listOf("Bad Group", "other")) {
            try {
                catalogue(apk, withOptional = true, groupOfSpanish = group)
                fail(group)
            } catch (e: CatalogueException) {
                assertTrue(e.problems.toString(), e.problems.any { "optionGroup" in it })
            }
        }
    }

    private fun key(): SigningKey {
        val ks = File(dir, "k.p12")
        val p = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-alias", "k", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "30", "-dname", "CN=test", "-keystore", ks.path, "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
        return SigningKey.fromKeyStore(ks, "changeit".toCharArray())
    }

    private val extra = mapOf("shminterop/Extra.smali" to ".class public Lshminterop/Extra;\n.super Ljava/lang/Object;\n")

    private fun request(apk: File, out: File? = null, key: SigningKey? = null, inj: Map<String, InjectionSource>? = null) =
        PatchRequest(apk, "syn", "p", "org.test", inj ?: mapOf("extra" to StagedInjection(extra, "org.test")), out, key, dir)

    @Test fun patchSignAndVerifyRoundTrip() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val out = File(dir, "out.apk")
        val report = Patcher(catalogue(apk)).patch(request(apk, out, key()))
        assertTrue(report.text(), report.ok)
        assertEquals("syn", report.family)
        assertEquals(1, report.injectedClasses)
        assertEquals(sha(out), report.outputSha256)
        assertTrue(report.phases.map { it.name }.containsAll(listOf("verify-input", "apply", "sign", "verify-signature", "verify-injections", "verify-anchors")))

        val verdict = ApkVerifier.Builder(out).build().verify()
        assertTrue(verdict.errors.toString(), verdict.isVerified)

        ZipReader(out).use { z ->
            assertEquals(listOf("classes.dex", "classes2.dex"), z.entries.map { it.name }.filter { it.endsWith(".dex") })
            val tree = DexTree(listOf("classes.dex" to z.read("classes.dex"), "classes2.dex" to z.read("classes2.dex")), api)
            assertTrue(tree.read(tree.classPath("com/example/syn/Main")!!).contains("\"patched\""))
            assertNotNull(tree.classPath("org/test/Extra"))
            assertEquals("plain", String(z.read("assets/readme.txt")))
            assertEquals(0, z.entry("assets/readme.txt")!!.method)
            assertNotNull(z.entry("META-INF/MANIFEST.MF"))
        }
    }

    @Test fun analyzeChangesNothingOnDisk() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val before = apk.readBytes()
        val report = Patcher(catalogue(apk)).analyze(request(apk))
        assertTrue(report.text(), report.ok)
        assertTrue(report.dryRun)
        assertTrue(report.phases.none { it.name == "sign" })
        assertTrue(before.contentEquals(apk.readBytes()))
        assertEquals(listOf("syn.apk"), dir.listFiles()!!.map { it.name }.filter { it.endsWith(".apk") })
    }

    @Test fun missingAnchorIsAReportedFailure() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val report = Patcher(catalogue(apk, old = "\\\"absent\\\"")).analyze(request(apk))
        assertFalse(report.ok)
        assertTrue(report.error, report.error!!.startsWith("apply"))
        assertTrue(report.patterns.any { it.error != null })
    }

    @Test fun wrongInputShaIsRejectedBeforeAnythingElse() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val cat = catalogue(apk)
        apk.appendBytes(byteArrayOf(0))
        val report = Patcher(cat).analyze(request(apk))
        assertFalse(report.ok)
        assertEquals(listOf("check-request", "verify-input"), report.phases.map { it.name })
    }

    @Test fun badPrefixIsRejectedBeforeReadingTheApk() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        for (bad in listOf("", "org..test", "1org.test", "org.test;", "org/test", "org.tëst")) {
            val req = PatchRequest(apk, "syn", "p", bad, mapOf("extra" to StagedInjection(extra, "org.test")), null, null, dir)
            val report = Patcher(catalogue(apk)).analyze(req)
            assertFalse(bad, report.ok)
            assertEquals(bad, listOf("check-request"), report.phases.map { it.name })
        }
    }

    @Test fun unknownProfileOrInputIsReported() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val inj = mapOf("extra" to StagedInjection(extra, "org.test"))
        for (req in listOf(PatchRequest(apk, "syn", "nope", "org.test", inj, null, null, dir),
                           PatchRequest(apk, "nope", "p", "org.test", inj, null, null, dir))) {
            val report = Patcher(catalogue(apk)).analyze(req)
            assertFalse(report.ok)
            assertEquals(listOf("check-request"), report.phases.map { it.name })
        }
    }

    @Test fun wrongPackageIsNotAKnownFamily() {
        val apk = File(dir, "syn.apk")
        syntheticApk(apk, pkg = "com.other.app")
        val report = Patcher(catalogue(apk)).analyze(request(apk))
        assertFalse(report.ok)
        assertEquals("detect-family", report.phases.last().name)
    }

    @Test fun injectionSetMustMatchTheProfile() {
        val apk = File(dir, "syn.apk").also { syntheticApk(it) }
        val report = Patcher(catalogue(apk)).analyze(request(apk, inj = emptyMap()))
        assertFalse(report.ok)
        assertEquals("check-injections", report.phases.last().name)
    }

    @Test fun apkWithoutDexIsRejected() {
        val apk = File(dir, "syn.apk")
        syntheticApk(apk, withDex = false)
        val report = Patcher(catalogue(apk)).analyze(request(apk))
        assertFalse(report.ok)
        assertEquals("unpack", report.phases.last().name)
    }

    // ---------------------------------------------------------------- zip layer

    @Test fun zipCopiesRawAndAligns() {
        val src = File(dir, "src.zip")
        val data = ByteArray(5000) { (it % 7).toByte() }
        ZipWriter(FileOutputStream(src)).use { z ->
            z.addBytes("a.txt", "x".toByteArray(), stored = true)
            z.addBytes("lib/arm64-v8a/libx.so", data, stored = true, align = 16384)
            z.addBytes("d.bin", data, stored = false)
            z.finish()
        }
        val dst = File(dir, "dst.zip")
        ZipReader(src).use { r ->
            ZipWriter(FileOutputStream(dst)).use { w ->
                for (e in r.entries) w.addRaw(r, e, if (e.method == 0) 4 else 1)
                w.finish()
            }
        }
        ZipReader(dst).use { r ->
            assertEquals(listOf("a.txt", "lib/arm64-v8a/libx.so", "d.bin"), r.entries.map { it.name })
            assertEquals("x", String(r.read("a.txt")))
            assertTrue(data.contentEquals(r.read("d.bin")))
            val crc = CRC32().also { it.update(data) }.value
            assertEquals(crc, r.entry("d.bin")!!.crc)
        }
        // the aligned copy keeps the .so data on a 16 KiB boundary
        ZipReader(src).use { r ->
            val so = r.entry("lib/arm64-v8a/libx.so")!!
            val bytes = src.readBytes()
            val at = bytes.indexOfSlice(data)
            assertTrue(at > 0 && at % 16384 == 0 && so.method == 0)
        }
    }

    @Test fun zipRejectsDuplicateNames() {
        val z = ZipWriter(FileOutputStream(File(dir, "dup.zip")))
        z.addBytes("a", ByteArray(1), stored = true)
        try {
            z.addBytes("a", ByteArray(1), stored = true)
            fail()
        } catch (e: Exception) {
            // expected
        } finally {
            z.close()
        }
    }

    // ---------------------------------------------------------------- prefix staging

    @Test fun prefixStagerMapsTextAndPaths() {
        val s = PrefixStager("com.example")
        assertEquals("Lcom/example/A;", s.text("Lshminterop/A;"))
        assertEquals("com.example.A", s.text("shminterop.A"))
        assertEquals("com/example/pkg/B.smali", s.path("shminterop/pkg/B.smali"))
        val staged = s.stage(mapOf("shminterop/C.smali" to ".class Lshminterop/C;"))
        assertEquals(mapOf("com/example/C.smali" to ".class Lcom/example/C;"), staged)
    }

    @Test fun prefixStagerRejectsBadPrefixAndCollisions() {
        for (bad in listOf("", "1abc", "a..b", "a b", "../x", "a/b")) {
            try {
                PrefixStager(bad)
                fail("accepted '$bad'")
            } catch (e: PatchFailure) {
                // expected
            }
        }
        try {
            PrefixStager("a.b").stage(mapOf("shminterop/A.smali" to "", "a/b/A.smali" to ""))
            fail()
        } catch (e: PatchFailure) {
            // expected
        }
    }

    private fun ByteArray.indexOfSlice(s: ByteArray): Int {
        outer@ for (i in 0..size - s.size) {
            for (j in s.indices) if (this[i + j] != s[j]) continue@outer
            return i
        }
        return -1
    }
}
