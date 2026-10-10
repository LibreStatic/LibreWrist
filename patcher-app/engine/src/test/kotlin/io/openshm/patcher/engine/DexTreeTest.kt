package io.openshm.patcher.engine

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DexTreeTest {
    private val api = 31
    private val cls2 = """.class public Lw2/k;
.super Ljava/lang/Object;

.method public static e(I)V
    .locals 1

    const-string v0, "needle-string-123"

    return-void
.end method
"""
    private fun tree(): DexTree {
        val d1 = DexTree.buildDex(mapOf("com/example/util/A.smali" to A_SMALI.replace(".abstract", ".abstract").replace(
            ".method public abstract nolocals()Z\n.end method\n", "")), api)
        val d2 = DexTree.buildDex(mapOf("w2/k.smali" to cls2), api)
        return DexTree(listOf("classes.dex" to d1, "classes2.dex" to d2), api)
    }

    @Test fun layoutLikeApktool() {
        val t = tree()
        assertEquals(listOf("smali/com/example/util/A.smali", "smali_classes2/w2/k.smali"), t.candidates())
        assertEquals("smali_classes2/w2/k.smali", t.classPath("w2/k"))
        assertNull(t.classPath("w2/none"))
        assertTrue(t.read("smali_classes2/w2/k.smali").startsWith(".class public Lw2/k;\n.super Ljava/lang/Object;\n"))
    }

    @Test fun needleFilterNeverMissesAndPrunes() {
        val t = tree()
        assertEquals(listOf("smali_classes2/w2/k.smali"), t.candidates(ClassFilter(requireAll = listOf("needle-string-123"))))
        assertEquals(listOf("smali/com/example/util/A.smali"), t.candidates(ClassFilter(requireAll = listOf("\"MARK\""))))
        assertEquals(2, t.candidates(ClassFilter(requireAny = listOf("\"MARK\"", "needle-string-123"))).size)
        assertEquals(2, t.candidates(ClassFilter(requireAll = listOf("ab"))).size) // too short to index
    }

    @Test fun opsPatchAndRebuildOnlyTheTouchedDex() {
        val t = tree()
        val ctx = PatchContext(t)
        op("replaceInClass", """{"className": "w2/k", "old": "needle-string-123", "new": "patched"}""").apply(ctx)
        val result = t.rebuild()
        assertEquals(listOf("classes2.dex"), result.changed.map { it.entryName })
        assertNull(result.injected)
        val dex = DexBackedDexFile(Opcodes.forApi(api), result.changed[0].bytes)
        assertEquals(listOf("Lw2/k;"), dex.classes.map { it.type })
        val back = DexTree(listOf("classes.dex" to DexTree.buildDex(mapOf("w2/k.smali" to cls2), api)), api)
        assertTrue("needle-string-123" in back.read("smali/w2/k.smali"))
        val patched = DexTree(listOf("classes.dex" to result.changed[0].bytes), api)
        assertTrue("\"patched\"" in patched.read("smali/w2/k.smali"))
        assertFalse("needle-string-123" in patched.read("smali/w2/k.smali"))
    }

    @Test fun removeDirAndInjection() {
        val t = tree()
        val ctx = PatchContext(t)
        op("removeSmaliDir", """{"rel": "w2"}""").apply(ctx)
        assertNull(t.classPath("w2/k"))
        val inject = InjectionSource { mapOf("com/example/inj/I.smali" to ".class public Lcom/example/inj/I;\n.super Ljava/lang/Object;\n") }
        val r = t.rebuild(listOf(inject))
        assertEquals(listOf("classes2.dex"), r.changed.map { it.entryName })
        assertEquals("classes3.dex", r.injected!!.entryName)
        assertNotNull(DexBackedDexFile(Opcodes.forApi(api), r.injected!!.bytes).classes.firstOrNull { it.type == "Lcom/example/inj/I;" })
    }

    @Test fun injectionOfAnExistingClassFails() {
        val t = tree()
        val inject = InjectionSource { mapOf("w2/k.smali" to ".class public Lw2/k;\n.super Ljava/lang/Object;\n") }
        try {
            t.rebuild(listOf(inject))
            org.junit.Assert.fail()
        } catch (e: PatchFailure) {
            assertTrue("already present" in e.message!!)
        }
    }

    @Test fun anUnassemblablePatchFails() {
        val t = tree()
        t.write("smali_classes2/w2/k.smali", "not smali at all")
        try {
            t.rebuild()
            org.junit.Assert.fail()
        } catch (e: PatchFailure) {
            assertTrue("does not assemble" in e.message!!)
        }
    }
}
