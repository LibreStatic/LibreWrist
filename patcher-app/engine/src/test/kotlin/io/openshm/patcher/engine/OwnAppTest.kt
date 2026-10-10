package io.openshm.patcher.engine

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** DexInjection and OwnAppBuilder over synthetic placeholder inputs (no vendor files, no device). */
class OwnAppTest {
    private lateinit var dir: File
    private val api = 21
    private val prefix = "org.test"
    private val digest = "ab".repeat(32)
    private val permission = "shminterop.shmbridge.permission.STATUS"

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "ownapp-test-" + System.nanoTime()).also { it.mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private val mainSmali = """
.class public Lshminterop/shmbridge/phone/Main;
.super Ljava/lang/Object;

.method public static name()Ljava/lang/String;
    .locals 1

    const-string v0, "shminterop.shmbridge.phone.status"

    return-object v0
.end method

.method public static other()Ljava/lang/String;
    .locals 1

    const-string v0, "plain"

    return-object v0
.end method
"""
    private val helperSmali = """
.class public Lshminterop/shmbridge/phone/Helper;
.super Ljava/lang/Object;

.method public static main()Ljava/lang/String;
    .locals 1

    invoke-static {}, Lshminterop/shmbridge/phone/Main;->name()Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method
"""
    private val classes = mapOf(
        "shminterop/shmbridge/phone/Main.smali" to mainSmali,
        "shminterop/shmbridge/phone/Helper.smali" to helperSmali,
    )
    private val libSmali = ".class public Lcom/other/Lib;\n.super Ljava/lang/Object;\n"

    // ------------------------------------------------------------------ DexInjection

    @Test fun dexInjectionStagesDescriptorsStringsAndPaths() {
        val out = DexInjection(DexTree.buildDex(classes, api), prefix).classes()
        assertEquals(
            setOf("org/test/shmbridge/phone/Main.smali", "org/test/shmbridge/phone/Helper.smali"),
            out.keys,
        )
        val main = out.getValue("org/test/shmbridge/phone/Main.smali")
        assertTrue(main, ".class public Lorg/test/shmbridge/phone/Main;" in main)
        assertTrue(main, "\"org.test.shmbridge.phone.status\"" in main)
        assertTrue(main, "\"plain\"" in main)
        val helper = out.getValue("org/test/shmbridge/phone/Helper.smali")
        assertTrue(helper, "Lorg/test/shmbridge/phone/Main;->name()" in helper)
        assertFalse(out.values.any { "shminterop" in it })
    }

    @Test fun dexInjectionInjectsIntoADexTree() {
        val host = DexTree(listOf("classes.dex" to DexTree.buildDex(mapOf("com/other/Lib.smali" to libSmali), api)), api)
        val rebuilt = host.rebuild(listOf(DexInjection(DexTree.buildDex(classes, api), prefix)))
        assertEquals(2, rebuilt.injectedClassCount)
        val dex = DexBackedDexFile(Opcodes.forApi(api), rebuilt.injected!!.bytes)
        assertEquals(setOf("Lorg/test/shmbridge/phone/Main;", "Lorg/test/shmbridge/phone/Helper;"), dex.classes.map { it.type }.toSet())
    }

    @Test fun dexInjectionRefusesForeignClassesAndEmptyDex() {
        val foreign = DexTree.buildDex(classes + ("com/other/Lib.smali" to libSmali), api)
        assertFailure("outside") { DexInjection(foreign, prefix).classes() }
        assertFailure("no classes") { DexInjection(DexTree.buildDex(emptyMap(), api), prefix).classes() }
        assertFailure("invalid package prefix") { DexInjection(DexTree.buildDex(classes, api), "1bad") }
    }

    // ------------------------------------------------------------------ own app

    private fun manifestBytes(extraPermission: Boolean = false, level: String = "signature", withStatus: Boolean = true, metas: Int = 1): ByteArray {
        val b = AndroidManifestBlock.empty()
        b.packageName = "shminterop.shmbridge.phone"
        b.minSdkVersion = 29
        b.getOrCreateApplicationElement()
        b.refresh()
        val m = BinaryManifest(b.bytes) { null }
        if (withStatus) m.root.addChild("permission", listOf("android:name" to permission, "android:protectionLevel" to level))
        if (extraPermission) m.root.addChild("permission", listOf("android:name" to "x.shmbridge.permission.STATUS", "android:protectionLevel" to "signature"))
        val app = m.root.children.first { it.tag == "application" }
        app.addChild("activity", listOf("android:name" to "shminterop.shmbridge.phone.Main", "android:exported" to "true"))
        val provider = app.addChild("provider", listOf("android:name" to "shminterop.shmbridge.phone.Status", "android:authorities" to "shminterop.shmbridge.phone.status"))
        repeat(metas) { provider.addChild("meta-data", listOf("android:name" to "shminterop.shmbridge.PATCHER_CERT", "android:value" to "")) }
        return m.toBytes()
    }

    private fun arscBytes(): ByteArray {
        val t = TableBlock()
        val p = t.newPackage(0x7f, "shminterop.shmbridge.phone")
        p.getOrCreate("", "string", "app_name").setValueAsString("Bridge")
        t.refresh()
        return t.bytes
    }

    private fun placeholderApk(
        manifest: ByteArray = manifestBytes(),
        dexes: List<ByteArray> = listOf(DexTree.buildDex(classes, api)),
        arscStored: Boolean = true,
    ): File {
        val f = File(dir, "placeholder.apk")
        ZipWriter(FileOutputStream(f)).use { z ->
            z.addBytes("AndroidManifest.xml", manifest, stored = false)
            z.addBytes("resources.arsc", arscBytes(), stored = arscStored, align = 4)
            dexes.forEachIndexed { i, d -> z.addBytes(if (i == 0) "classes.dex" else "classes${i + 1}.dex", d, stored = false) }
            z.addBytes("assets/readme.txt", "plain".toByteArray(), stored = true)
            z.finish()
        }
        return f
    }

    private fun catalogue(): Catalogue = Catalogue.parse(
        """
{
  "schema": 1, "serial": 1, "minAppVersion": 1,
  "inputs": [{"id": "syn", "file": "syn.apk", "sha256": "${"0".repeat(64)}", "label": "Synthetic", "package": "com.example.syn",
    "versionName": "1", "versionCode": 1, "minSdk": 21, "targetSdk": 21, "abis": [], "dpi": "nodpi", "format": "apk",
    "target": "phone", "role": "patched"}],
  "families": [{"id": "syn", "package": "com.example.syn"}],
  "profiles": {"p": {"description": "synthetic", "families": {"syn": []}, "inject": {}}},
  "patterns": {},
  "outputs": [
    {"id": "syn-out", "device": "phone", "package": "com.example.syn", "from": {"input": "syn", "profile": "p"}},
    {"id": "bridge-phone", "device": "phone", "package": "${'$'}{PKG_PREFIX}.shmbridge.phone", "from": {"ownApp": "shm-bridge/phone"}}
  ],
  "installOrder": [{"output": "syn-out"}, {"output": "bridge-phone"}], "grants": {"syn-out": [], "bridge-phone": []}, "stockAppsToDisable": []
}""",
    )

    private val signingKey: SigningKey by lazy { generateKey() }

    private fun generateKey(): SigningKey {
        val ks = File(dir, "k.p12")
        val p = ProcessBuilder(File(System.getProperty("java.home"), "bin/keytool").path, "-genkeypair", "-alias", "k", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "30", "-dname", "CN=test", "-keystore", ks.path, "-storetype", "PKCS12",
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
        return SigningKey.fromKeyStore(ks, "changeit".toCharArray())
    }

    private fun request(apk: File, out: File = File(dir, "out.apk"), outputId: String = "bridge-phone", prefix: String = this.prefix,
                        cert: String? = null, key: SigningKey = signingKey) =
        OwnAppRequest(apk, outputId, prefix, cert, out, key, dir)

    private fun outManifest(out: File) = ZipReader(out).use { BinaryManifest(it.read("AndroidManifest.xml")) { null } }
    private fun patcherCertMeta(m: BinaryManifest) = m.root.children.single { it.tag == "application" }
        .children.single { it.tag == "provider" }.children.single { it.tag == "meta-data" }

    private fun permissionOf(m: BinaryManifest) = m.root.children.single { it.tag == "permission" }

    private fun assertFailure(part: String, body: () -> Unit) {
        try {
            body()
            fail("expected a PatchFailure containing '$part'")
        } catch (e: PatchFailure) {
            assertTrue(e.message, part in e.message!!)
        }
    }

    @Test fun ownAppIsStagedSignedAndChecked() {
        val out = File(dir, "out.apk")
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(dexes = listOf(
            DexTree.buildDex(classes, api), DexTree.buildDex(mapOf("com/other/Lib.smali" to libSmali), api),
        )), out))
        assertTrue(report.text(), report.ok)
        assertEquals(3, report.classCount)
        assertEquals(Patcher.sha256(out), report.outputSha256)
        assertTrue(ApkVerifier.Builder(out).build().verify().isVerified)

        ZipReader(out).use { z ->
            val m = BinaryManifest(z.read("AndroidManifest.xml")) { null }
            assertEquals("org.test.shmbridge.phone", m.packageName)
            assertEquals("org.test.shmbridge.permission.STATUS", permissionOf(m).attr("android:name"))
            assertEquals("signature", permissionOf(m).attr("android:protectionLevel"))
            val app = m.root.children.first { it.tag == "application" }
            assertEquals("org.test.shmbridge.phone.Main", app.children.first { it.tag == "activity" }.attr("android:name"))
            assertEquals("org.test.shmbridge.phone.status", app.children.first { it.tag == "provider" }.attr("android:authorities"))
            assertFalse(m.poolStrings().any { "shminterop" in it })

            val arsc = ArscResourceTable(z.read("resources.arsc"))
            assertEquals(listOf("org.test.shmbridge.phone"), arsc.packageNames())
            assertEquals("Bridge", arsc.string("", "app_name"))
            val arscEntry = z.entry("resources.arsc")!!
            assertTrue(arscEntry.isStored)

            val tree = DexTree(listOf("classes.dex" to z.read("classes.dex"), "classes2.dex" to z.read("classes2.dex")), api)
            assertNull(tree.classPath("shminterop/shmbridge/phone/Main"))
            val main = tree.read(tree.classPath("org/test/shmbridge/phone/Main")!!)
            assertTrue(main, "\"org.test.shmbridge.phone.status\"" in main)
            val helper = tree.read(tree.classPath("org/test/shmbridge/phone/Helper")!!)
            assertTrue(helper, "Lorg/test/shmbridge/phone/Main;->name()" in helper)
            assertNotNull(tree.classPath("com/other/Lib"))
            assertEquals("plain", String(z.read("assets/readme.txt")))
        }
        assertTrue(report.phases.map { it.name }.containsAll(listOf("stage-dex", "stage-manifest", "stage-resources", "sign", "verify-signature", "verify-package", "verify-dex")))
    }

    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)

    @Test fun deflatedArscIsWrittenStored() {
        val out = File(dir, "out.apk")
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(arscStored = false), out))
        assertTrue(report.text(), report.ok)
        ZipReader(out).use { assertTrue(it.entry("resources.arsc")!!.isStored) }
    }

    @Test fun placeholderPrefixLeavesNamesAlone() {
        val out = File(dir, "out.apk")
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(), out, prefix = "shminterop"))
        assertTrue(report.text(), report.ok)
        val m = outManifest(out)
        assertEquals("shminterop.shmbridge.phone", m.packageName)
        ZipReader(out).use { z ->
            val tree = DexTree(listOf("classes.dex" to z.read("classes.dex")), api)
            assertNotNull(tree.classPath("shminterop/shmbridge/phone/Main"))
        }
    }

    @Test fun knownSignerEditIsAppliedOnce() {
        val out = File(dir, "out.apk")
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(), out, cert = digest))
        assertTrue(report.text(), report.ok)
        val p = permissionOf(outManifest(out))
        assertEquals("signature|knownSigner", p.attr("android:protectionLevel"))
        assertEquals(digest, p.attr("android:knownCerts"))
        assertEquals("org.test.shmbridge.permission.STATUS", p.attr("android:name"))
        assertEquals(digest, patcherCertMeta(outManifest(out)).attr("android:value"))
        assertEquals("org.test.shmbridge.PATCHER_CERT", patcherCertMeta(outManifest(out)).attr("android:name"))
    }

    @Test fun knownSignerIsNotAppliedWithoutACertificate() {
        val out = File(dir, "out.apk")
        assertTrue(OwnAppBuilder(catalogue()).build(request(placeholderApk(), out)).ok)
        assertNull(permissionOf(outManifest(out)).attr("android:knownCerts"))
        assertEquals("", patcherCertMeta(outManifest(out)).attr("android:value"))
    }

    @Test fun knownSignerNeedsExactlyOnePermission() {
        for (manifest in listOf(manifestBytes(withStatus = false), manifestBytes(extraPermission = true), manifestBytes(level = "normal"),
            manifestBytes(metas = 0), manifestBytes(metas = 2))) {
            val out = File(dir, "out.apk")
            val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(manifest), out, cert = digest))
            assertFalse(report.ok)
            assertTrue(report.error, "stage-manifest" in report.error!!)
            assertFalse(out.exists())
        }
    }

    @Test fun badDigestIsRefused() {
        for (bad in listOf("AB".repeat(32), "ab".repeat(31), "zz".repeat(32), "ab:".repeat(32))) {
            val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(), cert = bad))
            assertFalse(report.ok)
            assertTrue(report.error, report.error!!.startsWith("check-request"))
        }
    }

    @Test fun unknownOrNonOwnAppOutputIsRefused() {
        for (id in listOf("nope", "syn-out")) {
            val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(), outputId = id))
            assertFalse(report.ok)
            assertTrue(report.error, report.error!!.startsWith("check-request"))
        }
        assertFalse(OwnAppBuilder(catalogue()).build(request(placeholderApk(), prefix = "1bad")).ok)
    }

    @Test fun packageMismatchDeletesTheOutput() {
        // the catalogue expects <prefix>.shmbridge.phone; this placeholder has another package
        val b = AndroidManifestBlock.empty().also { it.packageName = "shminterop.other"; it.minSdkVersion = 29; it.getOrCreateApplicationElement(); it.refresh() }
        val out = File(dir, "out.apk")
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(b.bytes), out))
        assertFalse(report.text(), report.ok)
        assertTrue(report.error, "verify-package" in report.error!!)
        assertFalse(out.exists())
        assertEquals(null, report.outputSha256)
        assertEquals(emptyList<String>(), dir.listFiles()!!.map { it.name }.filter { it.startsWith("openshm-") })
    }

    @Test fun apkWithoutDexLeavesNoOutput() {
        val out = File(dir, "out.apk")
        // fails while unpacking, before anything is written
        val report = OwnAppBuilder(catalogue()).build(request(placeholderApk(dexes = emptyList()), out))
        assertFalse(report.ok)
        assertFalse(out.exists())
    }

    // ------------------------------------------------------------------ compiled-class staging

    private fun hx(v: Int) = if (v < 0) "-0x" + java.lang.Long.toHexString(-v.toLong()) else "0x" + Integer.toHexString(v)

    private fun switchClass(a: String, b: String): String {
        val entries = listOf(a to "sswitch_0", b to "sswitch_1").sortedBy { it.first.hashCode() }
            .joinToString("\n") { "        ${hx(it.first.hashCode())} -> :${it.second}" }
        return """
.class public Lshminterop/Sw;
.super Ljava/lang/Object;

.method public static f(Ljava/lang/String;)I
    .locals 2

    invoke-virtual {p0}, Ljava/lang/String;->hashCode()I

    move-result v0

    sparse-switch v0, :sswitch_data_0

    const/4 v0, -0x1

    return v0

    :sswitch_0
    const-string v1, "$a"

    invoke-virtual {p0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    const/4 v0, 0x1

    return v0

    :sswitch_1
    const-string v1, "$b"

    invoke-virtual {p0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    const/4 v0, 0x2

    return v0

    :sswitch_data_0
    .sparse-switch
$entries
    .end sparse-switch
.end method
"""
    }

    @Test fun stringSwitchKeysFollowTheRenamedStrings() {
        val src = switchClass("shminterop.ALARM", "plain")
        val staged = PrefixStager(prefix).smali(src)
        val expected = listOf("org.test.ALARM", "plain").sortedBy { it.hashCode() }.map { "        ${hx(it.hashCode())} -> :" }
        val table = staged.lines().dropWhile { it.trim() != ".sparse-switch" }.drop(1).takeWhile { it.trim() != ".end sparse-switch" }
        assertEquals(expected, table.map { it.substringBefore(":") + ":" })
        // each key still points at the label whose string produces it
        assertTrue(table.any { it.contains(hx("org.test.ALARM".hashCode())) && it.endsWith(":sswitch_0") })
        DexTree.buildDex(mapOf("org/test/Sw.smali" to staged.replace("Lshminterop/Sw;", "Lorg/test/Sw;")), api)
    }

    @Test fun switchesThatAreNotStringHashesAreLeftAlone() {
        // an int switch: keys do not hash the strings next to the labels
        val src = switchClass("shminterop.ALARM", "plain").replace("sparse-switch v0", "sparse-switch v0")
            .replace(Regex("0x[0-9a-f]+ -> :sswitch_0"), "0x5 -> :sswitch_0").replace(Regex("-?0x[0-9a-f]+ -> :sswitch_1"), "0x9 -> :sswitch_1")
        val staged = PrefixStager(prefix).smali(src)
        assertTrue(staged, "0x5 -> :sswitch_0" in staged && "0x9 -> :sswitch_1" in staged)
    }

    @Test fun anUnrecognisedStringSwitchFailsInsteadOfKeepingStaleKeys() {
        // every string load is too far from its label for rehash to tie it to the case
        val src = switchClass("shminterop.ALARM", "plain")
            .replace(Regex("    (:sswitch_\\d)\n"), "    $1\n" + "    nop\n".repeat(14))
        val e = org.junit.Assert.assertThrows(PatchFailure::class.java) { PrefixStager(prefix).smali(src) }
        assertTrue(e.message!!, "still the hash of a renamed string" in e.message!!)
    }

    @Test fun lambdaNamesFollowThePrefixWithDashes() {
        val staged = PrefixStager("com.example.oracle").smali(".method static lambda\$x\$0\$shminterop-shmbridge-phone-Hub()V\n")
        assertEquals(".method static lambda\$x\$0\$com-example-oracle-shmbridge-phone-Hub()V\n", staged)
    }
}
