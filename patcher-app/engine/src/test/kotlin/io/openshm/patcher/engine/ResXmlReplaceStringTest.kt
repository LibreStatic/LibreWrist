package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResXmlReplaceStringTest {
    @get:Rule val tmp = TemporaryFolder()

    private val auth = "com.x.auth"
    private val syncAdapter = xml("sync-adapter", "accountType" to auth, "contentAuthority" to "com.x.datastore", "other" to "$auth.sub")

    private fun xml(root: String, vararg attrs: Pair<String, String>): ByteArray {
        val doc = ResXmlDocument()
        val e = doc.newElement(root)
        for ((k, v) in attrs) e.createAttribute(k, 0).setValueAsString(v)
        doc.refreshFull()
        return doc.bytes
    }

    private fun manifest(): ByteArray {
        val b = AndroidManifestBlock.empty()
        b.packageName = auth
        b.getOrCreateApplicationElement()
        b.refresh()
        return b.bytes
    }

    private fun values(bytes: ByteArray): Map<String, String> {
        val doc = ResXmlDocument().also { it.readBytes(ByteArrayInputStream(bytes)) }
        return doc.recursiveAttributes().asSequence().filter { it.valueType == ValueType.STRING }
            .associate { it.name to it.valueAsString }
    }

    private fun apk(vararg entries: Triple<String, ByteArray, Boolean>): ZipReader {
        val f = tmp.newFile()
        ZipWriter(FileOutputStream(f)).use { z ->
            for ((n, b, stored) in entries) z.addBytes(n, b, stored)
            z.finish()
        }
        return ZipReader(f)
    }

    private fun op(old: String, new: String, expect: String? = null): Op {
        val args = j("""{"old": "$old", "new": "$new"}""").toMutableMap()
        if (expect != null) args["expect"] = if (expect.all { it.isDigit() }) expect.toLong() else expect
        return OpRegistry.prepare("resXmlReplaceString", args)
    }

    private fun ctx(zip: ZipReader) = PatchContext(
        MemorySmaliTree(), filesProvider = { ZipApkFiles(zip) },
    )

    @Test fun replacesExactValuesAndLeavesTheRest() {
        val untouched = xml("selector", "x" to "nothing here")
        val png = byteArrayOf(-119, 80, 78, 71) + auth.toByteArray()
        val zip = apk(
            Triple("AndroidManifest.xml", manifest(), false),
            Triple("res/Ab.xml", syncAdapter, false),
            Triple("res/Cd.xml", untouched, true),
            Triple("res/-0_.png", png, false),
        )
        val c = ctx(zip)
        val log = op(auth, "com.y.auth", "1").apply(c)
        assertEquals(listOf("res/Ab.xml :: $auth"), log)
        assertEquals(setOf("res/Ab.xml"), c.replacedEntries.keys)
        val v = values(c.replacedEntries.getValue("res/Ab.xml"))
        assertEquals("com.y.auth", v["accountType"])
        assertEquals("com.x.datastore", v["contentAuthority"])
        // a value that only contains the old one is not an exact match
        assertEquals("$auth.sub", v["other"])
    }

    @Test fun substringOnlyIsNotAMatch() {
        val zip = apk(Triple("res/Ab.xml", syncAdapter, false))
        val c = ctx(zip)
        try {
            op("com.x.data", "com.y.data").apply(c)
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "matched nothing" in e.message!!)
        }
        assertTrue(c.replacedEntries.isEmpty())
    }

    @Test fun expectMismatchFails() {
        val zip = apk(Triple("res/Ab.xml", syncAdapter, false))
        try {
            op(auth, "com.y.auth", "2").apply(ctx(zip))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "expected 2" in e.message!!)
        }
        op(auth, "com.y.auth", "any").apply(ctx(zip))
    }

    @Test fun manifestIsNeverTouched() {
        val zip = apk(Triple("AndroidManifest.xml", manifest(), false), Triple("res/Ab.xml", syncAdapter, false))
        val c = ctx(zip)
        op(auth, "com.y.auth").apply(c)
        assertFalse("AndroidManifest.xml" in c.replacedEntries)
    }

    @Test fun verifyAppliedChecksTheFinishedApk() {
        val before = apk(Triple("res/Ab.xml", syncAdapter, false))
        val c = ctx(before)
        val o = op(auth, "com.y.auth")
        o.apply(c)
        try {
            o.verifyApplied(ctx(before))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "still has the original value" in e.message!!)
        }
        val after = apk(Triple("res/Ab.xml", c.replacedEntries.getValue("res/Ab.xml"), false))
        assertEquals(listOf("res/Ab.xml :: com.y.auth"), o.verifyApplied(ctx(after)))
        // no file carries the new value: the patch is missing
        try {
            op(auth, "com.z.auth").verifyApplied(ctx(after))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "does not carry this patch" in e.message!!)
        }
    }

    @Test fun writeApkKeepsTheEntryMethodAndOtherEntries() {
        val other = ByteArray(300) { 7 }
        val src = apk(
            Triple("AndroidManifest.xml", manifest(), false),
            Triple("res/Ab.xml", syncAdapter, true),
            Triple("res/Cd.xml", syncAdapter, false),
            Triple("res/Ef.bin", other, false),
            Triple("classes.dex", ByteArray(8), false),
        )
        val c = ctx(src)
        op(auth, "com.y.auth", "2").apply(c)
        val out = File(tmp.root, "out.apk")
        Patcher.writeApk(src, out, null, null, emptyMap(), null, replaced = c.replacedEntries)
        ZipReader(out).use { r ->
            assertTrue(r.entry("res/Ab.xml")!!.isStored)
            assertFalse(r.entry("res/Cd.xml")!!.isStored)
            assertEquals("com.y.auth", values(r.read("res/Ab.xml"))["accountType"])
            assertEquals("com.y.auth", values(r.read("res/Cd.xml"))["accountType"])
            assertArrayEquals(other, r.read("res/Ef.bin"))
            assertNull(r.entry("res/missing"))
        }
    }
}
