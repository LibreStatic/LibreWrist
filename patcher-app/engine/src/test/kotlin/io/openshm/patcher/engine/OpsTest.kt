package io.openshm.patcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.app">
    <uses-permission android:name="p.ONE"/>
    <uses-permission android:name="p.TWO"/>
    <permission android:name="com.example.app.perm" android:protectionLevel="signature"/>
    <application android:label="@string/app_name">
        <activity android:name="com.example.app.Main">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
            </intent-filter>
        </activity>
        <service android:name="com.example.app.sync.SyncService"/>
        <receiver android:name="com.example.app.Boot"/>
        <meta-data android:name="m.one" android:value="1"/>
    </application>
</manifest>
"""

internal val A_SMALI = """.class public Lcom/example/util/A;
.super Ljava/lang/Object;

.method public static ok()Z
    .locals 1

    const-string v0, "MARK"

    return v0
.end method

.method public static other()Z
    .locals 1

    const/4 v0, 0x1

    return v0
.end method

.method public static valueOf()Lcom/example/util/E;
    .locals 1

    sget-object v0, Lcom/example/util/E;->a:Lcom/example/util/E;

    return-object v0
.end method

.method public static get()Lcom/example/util/E;
    .locals 2

    const/4 v0, 0x0

    return-object v0
.end method

.method public abstract nolocals()Z
.end method
"""

private val ENUM_SMALI = """.class public final enum Lcom/example/util/E;
.super Ljava/lang/Enum;

.method static constructor <clinit>()V
    .locals 3

    const-string v0, "OTHER"

    sput-object v1, Lcom/example/util/E;->x:Lcom/example/util/E;

    const-string v0, "NOT_SUPPORTED"

    new-instance v1, Lcom/example/util/E;

    sput-object v1, Lcom/example/util/E;->g:Lcom/example/util/E;

    return-void
.end method
"""

private val STORED_SMALI = """.class public Lcom/example/util/S;
.super Ljava/lang/Object;

.method public static stored()Lcom/example/util/E;
    .locals 2

    const-string v0, "Stored device version : "

    sget-object v0, Lcom/example/util/E;->x:Lcom/example/util/E;

    if-eq v1, v0, :ret

    return-object v1

    :ret
    return-object v0
.end method
"""

private const val TRUE = """["", "    const/4 v0, 0x1", "", "    return v0", ""]"""

private inline fun <reified E : Throwable> fails(block: () -> Unit): E {
    try {
        block()
    } catch (e: Throwable) {
        if (e is E) return e
        throw e
    }
    fail("expected ${E::class.simpleName}")
    throw AssertionError()
}

class OpArgumentsTest {
    @Test fun unknownOp() { fails<OpArgError> { OpRegistry.prepare("getattr", emptyMap()) } }

    @Test fun everyCatalogueOpIsRegistered() {
        val expected = setOf("adaptiveIconBadge", "addStringResources", "defineResourceEntry", "enumOrdinalsZero", "forceConstAfterMove", "forceInvokeResult",
            "insertAtMethodStart", "insertInClassMethod", "insertInMethodAtMatch", "manifestAddChild",
            "manifestAddQueryPackage", "manifestExpect", "manifestRebrand", "manifestRemove", "manifestRemoveAttr",
            "manifestSetAttr", "removeSmaliDir", "renameFile", "replaceAllInFilesWithAnchor", "replaceClassMethod",
            "replaceConstStringExact", "replaceInClass", "resXmlReplaceString", "returnSingleton", "setMethodBody", "swapConst",
            "sweepStringCompareAutoTrue", "verifyClassContains")
        assertEquals(expected, OpRegistry.names)
    }

    @Test fun renameFileOnlyChecksItsArguments() {
        // the app engine never recompiles resources, so the stored file stays as it is
        val ctx = PatchContext(MemorySmaliTree())
        val op = OpRegistry.prepare("renameFile", mapOf("path" to "res/drawable-xxhdpi/x.png", "name" to "x.webp"))
        assertTrue(op.apply(ctx).single().startsWith("res/drawable-xxhdpi/x.png :: kept as stored"))
        assertTrue(op.verifyApplied(ctx).single().startsWith("res/drawable-xxhdpi/x.png :: kept as stored"))
        // only the extension may change, and the path must be a resource file
        fails<OpArgError> { OpRegistry.prepare("renameFile", mapOf("path" to "res/drawable/y.png", "name" to "x.webp")) }
        fails<OpArgError> { OpRegistry.prepare("renameFile", mapOf("path" to "res/drawable/y.png", "name" to "y")) }
        fails<OpArgError> { OpRegistry.prepare("renameFile", mapOf("path" to "res/drawable/y.png", "name" to "y.")) }
        fails<OpArgError> { OpRegistry.prepare("renameFile", mapOf("path" to "assets/y.png", "name" to "y.webp")) }
    }

    @Test fun unknownMissingAndMistypedArguments() {
        assertTrue("unknown argument" in fails<OpArgError> { op("removeSmaliDir", """{"rel": "x", "extra": 1}""") }.message!!)
        assertTrue("missing" in fails<OpArgError> { op("removeSmaliDir", "{}") }.message!!)
        fails<OpArgError> { op("removeSmaliDir", """{"rel": 3}""") }
        fails<OpArgError> { op("swapConst", """{"fileRegex": "a", "old": "b", "new": "c", "expect": "many"}""") }
        fails<OpArgError> { op("manifestRemove", """{"select": {"path": "a", "bogus": 1}, "expect": 1}""") }
        fails<OpArgError> { op("manifestRemove", """{"select": {"path": "//"}, "expect": 1}""") }
        fails<OpArgError> { op("manifestRemove", """{"select": {"path": "a", "attr": {"x:y": "1"}}, "expect": 1}""") }
        fails<OpArgError> { op("swapConst", """{"fileRegex": "(", "old": "b", "new": "c"}""") }
    }

    @Test fun textArgsJoinLines() {
        val f = Fixture()
        f.smali.put("smali/A.smali", ".method public a()V\n    .locals 1\n\n    return-void\n.end method\n")
        f.apply("insertAtMethodStart", """{"sigRegex": ".*", "insert": ["", "    nop", ""]}""")
        assertTrue("    .locals 1\n\n    nop\n\n    return-void" in f.smali.get("smali/A.smali")!!)
    }
}

class InterpreterTest {
    private fun runner(ops: String, optional: Boolean = false): ProfileRunner {
        val steps = (Json.parse(ops) as List<*>).map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }
        return ProfileRunner(mapOf("t" to CataloguePattern("t", null, "t", "d", optional, steps)), "com.example")
    }

    @Test fun placeholdersAreExpanded() {
        assertEquals(mapOf("a" to listOf("com.example.x", "Lcom/example/y;")),
            Catalogue.expand(j("""{"a": ["${'$'}{PKG_PREFIX}.x", "L${'$'}{PKG_PATH}/y;"]}"""), "com.example"))
        fails<OpArgError> { Catalogue.expand("${'$'}{HOME}", "com.example") }
        assertEquals(mapOf("${'$'}{PKG_PREFIX}" to "{{x}} ${'$'}y"),
            Catalogue.expand(mapOf("${'$'}{PKG_PREFIX}" to "{{x}} ${'$'}y"), "com.example"))
    }

    @Test fun runReportShapeAndPlaceholder() {
        val f = Fixture()
        f.smali.put("smali/a/B.smali", "x \"com.example.y\" z\n")
        val report = runner("""[{"op": "replaceAllInFilesWithAnchor", "fileContains": "x",
            "old": "${'$'}{PKG_PREFIX}.y", "new": "Q"}]""").run(f.ctx, listOf("t"))
        assertEquals("t", report[0].id)
        assertEquals("d", report[0].description)
        assertTrue(report[0].log.isNotEmpty())
        assertTrue("\"Q\"" in f.smali.get("smali/a/B.smali")!!)
    }

    @Test fun emptyCombinedLogFailsUnlessOptional() {
        val f = Fixture()
        f.smali.put("smali/a/B.smali", ".class LB;\n")
        val ops = """[{"op": "swapConst", "fileRegex": "B", "old": "zzz", "new": "y", "expect": "any"}]"""
        val e = fails<PatternRequiredException> { runner(ops).run(f.ctx, listOf("t")) }
        assertTrue("matched nothing" in e.message!!)
        assertEquals("t", e.report[0].id)
        assertEquals(emptyList<String>(), runner(ops, optional = true).run(f.ctx, listOf("t"))[0].log)
    }

    @Test fun failingStepAbortsWithStepNumber() {
        val f = Fixture()
        f.smali.put("smali/a/B.smali", ".class LB;\n")
        val e = fails<PatternRequiredException> {
            runner("""[{"op": "swapConst", "fileRegex": "B", "old": "zzz", "new": "y"}]""", optional = true)
                .run(f.ctx, listOf("t"))
        }
        assertTrue(Regex("raised.*step 1 \\(swapConst\\)").containsMatchIn(e.message!!))
    }

    @Test fun unknownPatternId() {
        fails<PatternRequiredException> {
            runner("""[{"op": "removeSmaliDir", "rel": "x"}]""").run(Fixture().ctx, listOf("nope"))
        }
    }

    @Test fun validateReportsBadOpsAndReferences() {
        var text = java.io.File(System.getProperty("shm.repoRoot"), "catalogue/catalogue.json").readText()
        text = text.replaceFirst("\"op\": \"manifestExpect\"", "\"op\": \"eval\"")
        text = text.replaceFirst("\"constValue\"", "\"bogusArg\": 1, \"constValue\"")
        val last = text.lastIndexOf("\"alarm-provider-open\"")
        text = text.substring(0, last) + "\"missing-id\"" + text.substring(last + "\"alarm-provider-open\"".length)
        val problems = fails<CatalogueException> { Catalogue.parse(text) }.problems.joinToString("\n")
        for (needle in listOf("unknown op 'eval'", "unknown argument", "unknown pattern 'alarm-provider-open'", "pattern missing-id: not used")) {
            assertTrue("$needle in:\n$problems", needle in problems)
        }
    }

    @Test fun familyDetection() {
        val cat = Catalogue.parse(java.io.File(System.getProperty("shm.repoRoot"), "catalogue/catalogue.json").readText())
        val tree = MemorySmaliTree()
        val pkg = "com.samsung.android.shealthmonitor"
        assertEquals("shm-watch", cat.detectFamily(pkg, tree))
        tree.put("smali_classes3/com/samsung/android/shealthmonitor/version/api/AppVersionCheckResponse.smali", ".class LA;\n")
        assertEquals("shm-phone", cat.detectFamily(pkg, tree))
        assertEquals("alarm-watch", cat.detectFamily("com.samsung.android.watch.alarm", tree))
        assertEquals("unknown:other.app", cat.detectFamily("other.app", tree))
    }
}

class MethodOpsTest {
    private val path = "smali/com/example/util/A.smali"
    private fun fixture() = Fixture().also {
        it.smali.put(path, A_SMALI)
        it.smali.put("smali/com/example/other/B.smali", A_SMALI.replace("util/A", "other/B"))
    }

    @Test fun insertSelectorsAndExpect() {
        val f = fixture()
        val log = f.apply("insertAtMethodStart", """{"fileRegex": "/util/A\\.smali${'$'}", "sigRegex": ".*\\)Z",
            "bodyAll": ["\"MARK\""], "insert": ["", "    const/4 v0, 0x0", "", "    return v0", ""], "expect": 1}""")
        assertEquals(1, log.size)
        val src = f.smali.get(path)!!
        assertTrue("ok()Z\n    .locals 1\n\n    const/4 v0, 0x0\n\n    return v0\n\n    const-string" in src)
        assertEquals(3, Regex("return v0").findAll(src).count())
        assertEquals(A_SMALI.replace("util/A", "other/B"), f.smali.get("smali/com/example/other/B.smali"))
        assertEquals(1, f.verify("insertAtMethodStart", """{"fileRegex": "/util/A\\.smali${'$'}", "sigRegex": ".*\\)Z",
            "bodyAll": ["\"MARK\""], "insert": ["", "    const/4 v0, 0x0", "", "    return v0", ""], "expect": 1}""").size)
    }

    @Test fun expectCountMismatchFails() {
        for (expect in listOf(2, 0)) {
            fails<PatchFailure> {
                fixture().apply("insertAtMethodStart", """{"fileRegex": "/util/A", "sigRegex": ".*\\)Z",
                    "bodyAll": ["\"MARK\""], "insert": $TRUE, "expect": $expect}""")
            }
        }
    }

    @Test fun defaultExpectIsAtLeastOne() {
        val f = fixture()
        assertTrue("matched nothing" in fails<PatchFailure> {
            f.apply("insertAtMethodStart", """{"sigRegex": ".*nothing\\(\\)Z", "insert": $TRUE}""")
        }.message!!)
        assertEquals(emptyList<String>(), f.apply("insertAtMethodStart",
            """{"sigRegex": ".*nothing\\(\\)Z", "insert": $TRUE, "expect": "any"}"""))
    }

    @Test fun sigIsFullMatchAndExcludeBodyNone() {
        val f = fixture()
        assertEquals(emptyList<String>(), f.apply("insertAtMethodStart",
            """{"sigRegex": "public static ok", "insert": $TRUE, "expect": "any"}"""))
        assertEquals(emptyList<String>(), f.apply("insertAtMethodStart", """{"fileRegex": "util/A",
            "sigRegex": "public static .*\\)Z", "sigExclude": ["other"], "bodyNone": ["\"MARK\""],
            "insert": $TRUE, "expect": "any"}"""))
        assertEquals(emptyList<String>(), f.apply("insertAtMethodStart", """{"fileRegex": "util/A",
            "sigRegex": "public .*\\)Z", "sigExclude": ["other", "ok"], "insert": $TRUE, "expect": "any"}"""))
    }

    @Test fun needRegsTailAndIdempotency() {
        val f = fixture()
        val args = """{"fileRegex": "util/A", "sigRegex": "public static ok\\(\\)Z", "insert": ["", "    nop", ""],
            "needRegs": 5, "tail": ["    invoke-static {}, Lx;->t()V", "", ""], "expect": 1}"""
        f.apply("insertAtMethodStart", args)
        val src = f.smali.get(path)!!
        assertTrue("ok()Z\n    .locals 5\n\n    nop\n" in src)
        assertTrue("\"MARK\"\n\n    invoke-static {}, Lx;->t()V\n\n    return v0\n.end method" in src)
        assertTrue(f.apply("insertAtMethodStart", args)[0].startsWith(path))
        assertEquals(src, f.smali.get(path))
    }

    @Test fun returnSingletonUsesTheReturnType() {
        val f = fixture()
        assertEquals(1, f.apply("returnSingleton", """{"fileRegex": "util/A", "sigRegex": "public static get\\(\\).*",
            "field": "a", "expect": 1}""").size)
        assertTrue(".locals 2\n\n    sget-object v0, Lcom/example/util/E;->a:Lcom/example/util/E;\n\n" +
            "    return-object v0\n\n    const/4 v0, 0x0" in f.smali.get(path)!!)
        assertTrue("object type" in fails<PatchFailure> {
            f.apply("returnSingleton", """{"fileRegex": "util/A", "sigRegex": "public static ok.*", "field": "a"}""")
        }.message!!)
    }

    @Test fun setMethodBody() {
        val f = fixture()
        val args = """{"fileRegex": "util/A", "sigRegex": "public static other\\(\\)Z",
            "body": ["    .locals 1", "", "    const/4 v0, 0x0", "", "    return v0"], "expect": 1}"""
        f.apply("setMethodBody", args)
        assertTrue(".method public static other()Z\n    .locals 1\n\n    const/4 v0, 0x0\n\n    return v0\n.end method" in f.smali.get(path)!!)
        assertEquals(1, f.verify("setMethodBody", args).size)
        fails<PatchFailure> { f.apply("setMethodBody", """{"sigRegex": "public static other\\(\\)Z", "body": "x", "expect": 1}""") }
    }

    @Test fun insertAtMatchWithEnumField() {
        val f = fixture()
        f.smali.put("smali/com/example/util/E.smali", ENUM_SMALI)
        f.smali.put("smali/com/example/util/S.smali", STORED_SMALI)
        fun args(pattern: String = "sget-object (?P<reg>v\\\\d), (?P<etype>L[\\\\w/]+;)->\\\\w+:(?P=etype)\\\\n\\\\n" +
            "    if-eq (?P<stored>v\\\\d), (?P=reg), :(?P<label>\\\\w+)\\\\n\\\\n(?P<at>    return-object (?P=stored)\\\\n)",
            insert: String = """["    sget-object {{reg}}, {{etype}}->{{field}}:{{etype}}", "", "    if-eq {{stored}}, {{reg}}, :{{label}}", "", ""]""") =
            """{"fileRegex": "util/S", "sigRegex": "public static \\w+\\(\\)L.*;", "bodyAll": ["\"Stored device version : \""],
               "expect": 1, "pattern": "$pattern", "insert": $insert,
               "enumFields": {"field": {"enumType": "etype", "constant": "NOT_SUPPORTED"}}}"""
        f.apply("insertInMethodAtMatch", args())
        val src = f.smali.get("smali/com/example/util/S.smali")!!
        assertTrue("    sget-object v0, Lcom/example/util/E;->g:Lcom/example/util/E;\n\n    if-eq v1, v0, :ret\n\n    return-object v1" in src)
        f.apply("insertInMethodAtMatch", args())
        assertEquals(src, f.smali.get("smali/com/example/util/S.smali"))
        assertEquals(1, f.verify("insertInMethodAtMatch", args()).size)
        assertTrue("unknown template variable" in fails<PatchFailure> { f.apply("insertInMethodAtMatch", args(insert = "\"{{nope}}\"")) }.message!!)
        assertTrue("not found" in fails<PatchFailure> { f.apply("insertInMethodAtMatch", args(pattern = "(?P<at>nomatch)")) }.message!!)
        fails<OpArgError> { op("insertInMethodAtMatch", args(pattern = "no named group")) }
    }
}

class FileOpsTest {
    @Test fun replaceAllInFilesWithAnchor() {
        val f = Fixture()
        f.smali.put("smali/a.smali", "ANCHOR x x\n")
        f.smali.put("smali/b.smali", "x x\n")
        assertEquals(1, f.apply("replaceAllInFilesWithAnchor", """{"fileContains": "ANCHOR", "old": "x", "new": "Z", "expect": 1}""").size)
        assertEquals("ANCHOR Z Z\n", f.smali.get("smali/a.smali"))
        assertEquals("x x\n", f.smali.get("smali/b.smali"))
        fails<PatchFailure> { f.apply("replaceAllInFilesWithAnchor", """{"fileContains": "NOPE", "old": "A", "new": "B"}""") }
    }

    @Test fun swapConstRequiresExactlyOneOccurrenceAndContext() {
        val f = Fixture()
        f.smali.put("smali/x/c.smali", "const/16 v0, 0x16\n")
        f.smali.put("smali/x/d.smali", "const/16 v0, 0x16\nconst/16 v0, 0x16\n")
        f.apply("swapConst", """{"fileRegex": "/x/", "old": "const/16 v0, 0x16", "new": "const/16 v0, 0x10", "expect": 1}""")
        assertTrue("0x10" in f.smali.get("smali/x/c.smali")!!)
        assertEquals(2, Regex("0x16").findAll(f.smali.get("smali/x/d.smali")!!).count())
        fails<PatchFailure> { f.apply("swapConst", """{"fileRegex": "/x/c", "old": "0x10", "new": "0x9", "context": "absent"}""") }
    }

    @Test fun sweepStringCompare() {
        val f = Fixture()
        f.smali.put("smali/m/Check.smali", "const-string v1, \"samsung\"\n\ninvoke-virtual {v2, v1}, " +
            "Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z\n")
        f.smali.put("smali/androidx/Skip.smali", "const-string v1, \"samsung\"\ninvoke-virtual {v2, v1}, " +
            "Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n")
        val args = """{"constValue": "samsung", "replaceValue": "android", "excludeRegex": ["/androidx/"], "expect": 1}"""
        f.apply("sweepStringCompareAutoTrue", args)
        assertEquals("const-string v1, \"android\"\n\ninvoke-virtual {v1, v1}, " +
            "Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z\n", f.smali.get("smali/m/Check.smali"))
        assertTrue("\"samsung\"" in f.smali.get("smali/androidx/Skip.smali")!!)
        fails<PatchFailure> { f.apply("sweepStringCompareAutoTrue", """{"constValue": "android", "compare": ["equals"]}""") }
        assertEquals(1, f.verify("sweepStringCompareAutoTrue", args).size)
    }

    @Test fun replaceConstStringExact() {
        val f = Fixture()
        f.smali.put("smali/a.smali", "const-string v0, \"pkg\"\nconst-string v0, \"pkg.sub\"\n")
        f.apply("replaceConstStringExact", """{"old": "pkg", "new": "NEW", "expect": 1}""")
        assertEquals("const-string v0, \"NEW\"\nconst-string v0, \"pkg.sub\"\n", f.smali.get("smali/a.smali"))
        assertEquals(1, f.verify("replaceConstStringExact", """{"old": "pkg", "new": "NEW", "expect": 1}""").size)
    }

    @Test fun replaceConstStringExactWideRegistersAndJumbo() {
        val f = Fixture()
        f.smali.put("smali/b.smali", "const-string v12, \"pkg\"\nconst-string/jumbo p3, \"pkg\"\n")
        val args = """{"old": "pkg", "new": "NEW", "expect": 1}"""
        f.apply("replaceConstStringExact", args)
        assertEquals("const-string v12, \"NEW\"\nconst-string/jumbo p3, \"NEW\"\n", f.smali.get("smali/b.smali"))
        // the rebuilt dex can move the new string past index 0xFFFF: the self-check reads it as /jumbo
        f.smali.put("smali/b.smali", "const-string/jumbo v12, \"NEW\"\n")
        assertEquals(1, f.verify("replaceConstStringExact", args).size)
        // a wide-register original left behind is still caught
        f.smali.put("smali/c.smali", "const-string v10, \"pkg\"\n")
        fails<PatchFailure> { f.verify("replaceConstStringExact", args) }
    }

    @Test fun enumOrdinalsZeroAndForceOps() {
        val f = Fixture()
        f.smali.put("smali/e/E.smali", "const/4 v0, 0x1\nconst/4 v1, 0x2\nconst/4 v1, 0x2\n")
        f.apply("enumOrdinalsZero", """{"fileRegex": "e/E", "values": ["0x1", "0x2"], "expect": 1}""")
        assertEquals("const/4 v0, 0x0\nconst/4 v1, 0x2\nconst/4 v1, 0x2\n", f.smali.get("smali/e/E.smali"))

        f.smali.put("smali/f/F.smali", "invoke-static {}, La;->x()V\nmove-result v0\nANCHOR\n")
        val args = """{"anchor": "ANCHOR", "expect": 1}"""
        f.apply("forceConstAfterMove", args)
        assertEquals("invoke-static {}, La;->x()V\nmove-result v0\n\n    const/4 v0, 0x1\nANCHOR\n", f.smali.get("smali/f/F.smali"))
        assertEquals(1, f.verify("forceConstAfterMove", args).size)

        f.smali.put("smali/g/G.smali", "KEY\ninvoke-static {v1}, Lb;->c()Z\n    move-result v2\nend\n")
        val a2 = """{"fileAnchor": "KEY", "invokeRegex": "Lb;->c\\(\\)Z", "expect": 1}"""
        f.apply("forceInvokeResult", a2)
        assertTrue("move-result v2\n\n    const/4 v2, 0x1" in f.smali.get("smali/g/G.smali")!!)
        assertEquals(1, f.verify("forceInvokeResult", a2).size)
    }

    @Test fun verifyClassContains() {
        val f = Fixture()
        f.smali.put("smali/a/B.smali", ".class La/B;\n.field m:J\n")
        f.apply("verifyClassContains", """{"checks": [{"className": "a/B", "contains": " m:J"}]}""")
        assertTrue("missing" in fails<PatchFailure> {
            f.apply("verifyClassContains", """{"checks": [{"className": "a/B", "contains": "zz"}]}""")
        }.message!!)
        assertTrue("not found" in fails<PatchFailure> {
            f.apply("verifyClassContains", """{"checks": [{"className": "a/C", "contains": "zz"}]}""")
        }.message!!)
    }
}

class ClassOpsTest {
    private val path = "smali_classes2/w2/k.smali"
    private val smali = """.class public Lw2/k;
.super Ljava/lang/Object;

.method public onDestroy()V
    .locals 2

    const-string v0, "old"

    return-void
.end method

.method public static e(I)V
    .locals 1

    return-void
.end method
"""
    private fun fixture() = Fixture().also { it.smali.put(path, smali) }

    @Test fun replaceInClassExactCount() {
        val f = fixture()
        f.apply("replaceInClass", """{"className": "w2/k", "old": "\"old\"", "new": "\"new\""}""")
        assertTrue("\"new\"" in f.smali.get(path)!!)
        assertEquals(1, f.verify("replaceInClass", """{"className": "w2/k", "old": "\"old\"", "new": "\"new\""}""").size)
    }

    @Test fun replaceInClassWrongCountFails() {
        fails<PatchFailure> { fixture().apply("replaceInClass", """{"className": "w2/k", "old": "\"old\"", "new": "\"new\"", "count": 2}""") }
        fails<PatchFailure> { fixture().apply("replaceInClass", """{"className": "w2/k", "old": "\"missing\"", "new": "\"new\""}""") }
    }

    @Test fun missingClassFails() {
        fails<PatchFailure> { fixture().apply("replaceInClass", """{"className": "w2/zz", "old": "a", "new": "b"}""") }
    }

    @Test fun insertAfterLocals() {
        val f = fixture()
        f.apply("insertInClassMethod", """{"className": "w2/k", "header": "public onDestroy()V", "insert": "\n    nop\n"}""")
        assertTrue(".locals 2\n\n    nop\n\n    const-string" in f.smali.get(path)!!)
        fails<PatchFailure> { f.apply("insertInClassMethod", """{"className": "w2/k", "header": "public nope()V", "insert": "x"}""") }
    }

    @Test fun replaceMethodBodyAndGuard() {
        val f = fixture()
        f.apply("replaceClassMethod", """{"className": "w2/k", "header": "public static e(I)V", "body": "    .locals 0\n    return-void"}""")
        val src = f.smali.get(path)!!
        assertTrue(".method public static e(I)V\n    .locals 0\n    return-void\n.end method" in src)
        assertTrue("const-string v0, \"old\"" in src)
        fails<PatchFailure> {
            f.apply("replaceClassMethod", """{"className": "w2/k", "header": "public onDestroy()V", "body": "x",
                "mustContain": ["not-in-body"]}""")
        }
    }

    @Test fun removeSmaliDir() {
        val f = fixture()
        f.smali.put("smali/dropme/A.smali", ".class LA;")
        f.apply("removeSmaliDir", """{"rel": "dropme"}""")
        assertFalse(f.smali.paths().any { it.startsWith("smali/dropme") })
        fails<PatchFailure> { f.apply("removeSmaliDir", """{"rel": "dropme"}""") }
        f.verify("removeSmaliDir", """{"rel": "dropme"}""")
    }
}

class ManifestOpsTest {
    private fun fixture() = Fixture(manifest = DomManifest(MANIFEST))

    @Test fun removeEachListed() {
        val f = fixture()
        f.apply("manifestRemove", """{"select": {"path": "uses-permission", "attrIn": {"android:name": ["p.ONE", "p.TWO"]}},
            "expect": "eachListed"}""")
        assertEquals(emptyList<String?>(), f.manifest!!.names("uses-permission"))
        fails<PatchFailure> {
            f.apply("manifestRemove", """{"select": {"path": "application/meta-data",
                "attrIn": {"android:name": ["m.one", "m.two"]}}, "expect": "eachListed"}""")
        }
        assertEquals(listOf<String?>("m.one"), f.manifest!!.names("meta-data"))
    }

    @Test fun simpleNameOverSeveralPaths() {
        val f = fixture()
        f.apply("manifestRemove", """{"select": {"path": ["application/service", "application/receiver"],
            "simpleNameIn": {"android:name": ["SyncService", "Boot"]}}, "expect": "eachListed"}""")
        assertEquals(emptyList<String?>(), f.manifest!!.names("service") + f.manifest!!.names("receiver"))
        assertEquals(listOf<String?>("com.example.app.Main"), f.manifest!!.names("activity"))
    }

    @Test fun countsAreAsserted() {
        val f = fixture()
        val sel = """{"path": "uses-permission"}"""
        f.apply("manifestExpect", """{"select": $sel, "expect": 2}""")
        f.apply("manifestExpect", """{"select": $sel, "expect": "atLeastOne"}""")
        for (e in listOf(1, 3, 0)) fails<PatchFailure> { f.apply("manifestExpect", """{"select": $sel, "expect": $e}""") }
        fails<PatchFailure> { f.apply("manifestRemove", """{"select": {"path": "service"}, "expect": 1}""") }
    }

    @Test fun setAttrOnRootAndByAttribute() {
        val f = fixture()
        f.apply("manifestSetAttr", """{"select": {"path": ".", "attr": {"package": "com.example.app"}},
            "attrs": {"package": "new.pkg"}, "expect": 1}""")
        f.apply("manifestSetAttr", """{"select": {"path": "application", "attr": {"android:label": "@string/app_name"}},
            "attrs": {"android:label": "@string/x", "android:icon": "@mipmap/i"}, "expect": 1}""")
        val m = f.manifest!!.text()
        assertTrue("package=\"new.pkg\"" in m)
        assertTrue("android:label=\"@string/x\"" in m)
        assertTrue("android:icon=\"@mipmap/i\"" in m)
        fails<PatchFailure> {
            f.apply("manifestSetAttr", """{"select": {"path": ".", "attr": {"package": "com.example.app"}},
                "attrs": {"package": "x"}, "expect": 1}""")
        }
        assertEquals(1, f.verify("manifestSetAttr", """{"select": {"path": "application", "attr": {"android:label": "@string/app_name"}},
            "attrs": {"android:label": "@string/x", "android:icon": "@mipmap/i"}, "expect": 1}""").size)
    }

    @Test fun removeAttrRequiresTheAttribute() {
        val f = fixture()
        val args = """{"select": {"path": "permission", "attr": {"android:name": "com.example.app.perm"}},
            "attrs": ["android:protectionLevel"], "expect": 1}"""
        f.apply("manifestRemoveAttr", args)
        assertFalse("protectionLevel" in f.manifest!!.text())
        assertTrue("has no" in fails<PatchFailure> { f.apply("manifestRemoveAttr", args) }.message!!)
        assertEquals(1, f.verify("manifestRemoveAttr", args).size)
    }

    @Test fun childMatchSelectsOnlyTheLauncherFilter() {
        val f = fixture()
        val args = """{"select": {"path": "application/activity/intent-filter",
            "parentAttr": {"android:name": "com.example.app.Main"},
            "hasChild": [{"tag": "category", "attr": {"android:name": "android.intent.category.LAUNCHER"}},
                         {"tag": "action", "attr": {"android:name": "android.intent.action.MAIN"}}]}, "expect": 1}"""
        f.apply("manifestRemove", args)
        val m = f.manifest!!.text()
        assertFalse("LAUNCHER" in m)
        assertTrue("android.intent.action.VIEW" in m)
        fails<PatchFailure> { f.apply("manifestRemove", args) }
        assertEquals(m, f.manifest!!.text())
        f.verify("manifestRemove", args)
    }

    @Test fun addChildWithNestedChildrenAndAbsenceCheck() {
        val f = fixture()
        val absent = """{"select": {"path": "**", "attr": {"android:name": "com.example.app.New"}}, "expect": 0}"""
        f.apply("manifestExpect", absent)
        val add = """{"parent": {"path": "application"}, "tag": "receiver",
            "attrs": {"android:name": "com.example.app.New", "android:exported": "true"},
            "children": [{"tag": "intent-filter", "children": [{"tag": "action", "attrs": {"android:name": "com.example.IN"}}]}]}"""
        f.apply("manifestAddChild", add)
        val m = f.manifest!!.text()
        val receiver = f.manifest!!.all("receiver").last()
        assertEquals("com.example.app.New", receiver.attr("android:name"))
        assertEquals("true", receiver.attr("android:exported"))
        assertEquals("com.example.IN", receiver.children[0].children[0].attr("android:name"))
        assertTrue(m.indexOf("m.one") < m.indexOf("com.example.app.New"))
        fails<PatchFailure> { f.apply("manifestExpect", absent) }
        fails<PatchFailure> { f.apply("manifestAddChild", """{"parent": {"path": "application/activity/nothing"}, "tag": "x"}""") }
        assertEquals(1, f.verify("manifestAddChild", add).size)
    }

    @Test fun rebrandRewritesOnlyPackageAndPermissionLikeAttributes() {
        val f = fixture()
        f.manifest!!.all("permission")[0].setAttr("android:permission", "com.example.app.p")
        f.apply("manifestRebrand", """{"oldPkg": "com.example.app", "newPkg": "net.x.app"}""")
        val m = f.manifest!!
        assertEquals("net.x.app", m.root.attr("package"))
        assertEquals(listOf<String?>("net.x.app.perm"), m.names("permission"))
        assertEquals("net.x.app.p", m.all("permission")[0].attr("android:permission"))
        assertEquals(listOf<String?>("com.example.app.Main"), m.names("activity"))
        assertEquals(1, f.verify("manifestRebrand", """{"oldPkg": "com.example.app", "newPkg": "net.x.app"}""").size)
        assertEquals(emptyList<String>(), f.apply("manifestRebrand", """{"oldPkg": "com.example.app", "newPkg": "net.x.app"}"""))
    }

    @Test fun addQueryPackage() {
        val f = Fixture(manifest = DomManifest("""<manifest xmlns:android="$ANDROID_NS"><queries><intent/></queries></manifest>"""))
        f.apply("manifestAddQueryPackage", """{"pkg": "a.b"}""")
        assertEquals("package", f.manifest!!.root.children[0].children[0].tag)
        assertTrue("already declares" in f.apply("manifestAddQueryPackage", """{"pkg": "a.b"}""")[0])
        val none = Fixture(manifest = DomManifest("""<manifest xmlns:android="$ANDROID_NS"/>"""))
        fails<PatchFailure> { none.apply("manifestAddQueryPackage", """{"pkg": "a.b"}""") }
    }

    @Test fun addStringResources() {
        val f = Fixture(resources = MapResources(mapOf("" to mutableMapOf(), "-es" to mutableMapOf())))
        val args = """{"name": "n", "values": {"": "Phone", "-es": "Teléfono"}}"""
        f.apply("addStringResources", args)
        assertEquals("Teléfono", f.resources!!.string("-es", "n"))
        assertEquals(2, f.verify("addStringResources", args).size)
        fails<PatchFailure> { f.apply("addStringResources", args) }
        fails<PatchFailure> { f.apply("addStringResources", """{"name": "m", "values": {"-fr": "x"}}""") }
        assertNotEquals(null, f.resources!!.string("", "n"))
    }

    /** A table with `anim` (type 0x01, entry 0 empty, entry 1 defined) and `string` (type 0x02) in two configurations. */
    private fun animTable(): ArscResourceTable {
        val t = com.reandroid.arsc.chunk.TableBlock()
        val p = t.newPackage(0x7f, "com.example.app")
        p.getOrCreate("", "anim", "fade").setValueAsReference(0x7f020000)
        p.getOrCreate("-es", "anim", "fade").setValueAsReference(0x7f020000)
        p.getOrCreate("", "string", "app_name").setValueAsString("App")
        t.refresh()
        return ArscResourceTable(t.bytes)
    }

    private fun arscCtx(r: ArscResourceTable) = PatchContext(MemorySmaliTree(), resourcesProvider = { r })

    @Test fun defineResourceEntry() {
        val args = """{"type": "string", "name": "anchor", "id": "0x7f0100ff"}"""
        // type 0x01 is whatever ARSCLib numbered first; find the anim id
        val r = animTable()
        val animId = (0x7f000000 or (r.table.pickOne()!!.getSpecTypePair("anim")!!.id shl 16))
        val idText = "0x%08x".format(animId + 5)
        val okArgs = """{"type": "anim", "name": "anchor", "id": "$idText"}"""
        val ctx = arscCtx(r)
        fails<PatchFailure> { op("defineResourceEntry", okArgs).verifyApplied(ctx) }
        op("defineResourceEntry", okArgs).apply(ctx)
        val back = ArscResourceTable(r.toBytes())
        assertEquals("anim/anchor", back.entryAt(animId + 5))
        assertEquals(1, op("defineResourceEntry", okArgs).verifyApplied(arscCtx(back)).size)
        assertEquals("anim/fade", back.entryAt(animId))
        // default config only, @null = reference with data 0
        val blocks = back.table.pickOne()!!.getSpecTypePair("anim")!!.typeBlocks
        val seen = HashMap<String, com.reandroid.arsc.value.Entry?>()
        while (blocks.hasNext()) blocks.next().let { seen[it.resConfig.qualifiers] = it.getEntry(5)?.takeIf { e -> !e.isNull } }
        assertNotEquals(null, seen[""])
        assertEquals(null, seen["-es"])
        assertEquals(com.reandroid.arsc.value.ValueType.REFERENCE, seen[""]!!.valueType)
        assertEquals(0, seen[""]!!.resValue.data)
        // slot or name taken, wrong type
        fails<PatchFailure> { op("defineResourceEntry", okArgs).apply(arscCtx(back)) }
        fails<PatchFailure> { op("defineResourceEntry", """{"type": "anim", "name": "other", "id": "$idText"}""").apply(arscCtx(back)) }
        fails<PatchFailure> { op("defineResourceEntry", """{"type": "anim", "name": "fade", "id": "0x%08x"}""".format(animId + 6)).apply(arscCtx(back)) }
        fails<PatchFailure> { op("defineResourceEntry", """{"type": "anim", "name": "x", "id": "0x7f0900ff"}""").apply(arscCtx(back)) }
        fails<PatchFailure> { op("defineResourceEntry", args).apply(arscCtx(animTable())) }
        fails<PatchFailure> { op("defineResourceEntry", """{"type": "anim", "name": "x", "id": "0x7e010000"}""").apply(arscCtx(back)) }
        fails<OpArgError> { op("defineResourceEntry", """{"type": "anim", "name": "x", "id": "7f010000"}""") }
        fails<OpArgError> { op("defineResourceEntry", """{"type": "anim", "name": "x", "id": "0x7f01"}""") }
    }
}
