package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdaptiveIconBadgeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ring = """M61.5,71.5a10,10 0,1 1,20 0a10,10 0,1 1,-20 0"""
    private val layersJson = """{
        "foreground": [
            {"pathData": "$ring", "fillColor": "#FF4F5BD5", "strokeColor": "#FFFFFFFF", "strokeWidth": 1.5},
            {"pathData": "M1,1 L2,2", "strokeColor": "#FFFFFFFF", "strokeWidth": 1, "strokeLineCap": "round", "strokeLineJoin": "bevel"}
        ],
        "monochrome": [
            {"pathData": "$ring", "strokeColor": "#FFFFFFFF", "strokeWidth": 1.5}
        ]
    }"""

    private fun id(name: String) = FrameworkIds.attr(name)!!

    private fun ResXmlElement.android(name: String) = createAndroidAttribute(name, id(name))

    /** An adaptive icon whose foreground uses a drawable attribute and whose monochrome (if any) an inline <inset>. */
    private fun icon(foreground: String, monochrome: String? = null, inlineForeground: Boolean = false): ByteArray {
        val doc = ResXmlDocument()
        val root = doc.newElement("adaptive-icon")
        root.getOrCreateNamespace("http://schemas.android.com/apk/res/android", "android")
        val fg = root.newElement("foreground")
        if (inlineForeground) {
            val inset = fg.newElement("inset")
            inset.android("drawable").apply { valueType = ValueType.REFERENCE; data = 0x7f080001 }
            inset.android("insetLeft").apply { valueType = ValueType.DIMENSION; data = (18 shl 8) or 1 }
        } else {
            fg.android("drawable").apply { valueType = ValueType.REFERENCE; data = 0x7f080001 }
        }
        if (monochrome != null) {
            root.newElement("monochrome").android("drawable").apply { valueType = ValueType.REFERENCE; data = 0x7f080002 }
        }
        doc.refreshFull()
        return doc.bytes
    }

    private fun parse(b: ByteArray) = ResXmlDocument().also { it.readBytes(ByteArrayInputStream(b)) }

    private fun kids(e: ResXmlElement, tag: String): List<ResXmlElement> =
        e.elements.asSequence().filter { it.name == tag }.toList()

    private fun apk(vararg entries: Pair<String, ByteArray>): ZipReader {
        val f = tmp.newFile()
        ZipWriter(FileOutputStream(f)).use { z ->
            for ((n, b) in entries) z.addBytes(n, b, false)
            z.finish()
        }
        return ZipReader(f)
    }

    private fun ctx(zip: ZipReader) = PatchContext(MemorySmaliTree(), filesProvider = { ZipApkFiles(zip) })

    private fun op(expect: String? = null, layers: String = layersJson): Op {
        val args = j("""{"layers": $layers}""").toMutableMap()
        if (expect != null) args["expect"] = if (expect.all { it.isDigit() }) expect.toLong() else expect
        return OpRegistry.prepare("adaptiveIconBadge", args)
    }

    private fun attr(e: ResXmlElement, name: String) = e.searchAttributeByResourceId(id(name))!!

    @Test fun drawableAttributeLayerBecomesLayerListWithTheBadge() {
        val zip = apk(
            "res/Ab.xml" to icon("a", monochrome = "m"),
            "res/Cd.xml" to icon("a"),
            "res/Ef.xml" to ByteArray(30) { 3 },
        )
        val c = ctx(zip)
        val log = op("2").apply(c)
        assertEquals(listOf("res/Ab.xml :: foreground,monochrome", "res/Cd.xml :: foreground"), log)

        val root = parse(c.replacedEntries.getValue("res/Ab.xml")).documentElement
        val fg = kids(root, "foreground").single()
        assertEquals(null, fg.searchAttributeByResourceId(id("drawable")))
        val list = kids(fg, "layer-list").single()
        val items = kids(list, "item")
        assertEquals(2, items.size)
        // item 1 carries the original reference
        val orig = attr(items[0], "drawable")
        assertEquals(ValueType.REFERENCE, orig.valueType)
        assertEquals(0x7f080001, orig.data)
        // item 2 is the vector
        val vector = kids(items[1], "vector").single()
        assertEquals(ValueType.DIMENSION, attr(vector, "width").valueType)
        assertEquals((108 shl 8) or 1, attr(vector, "width").data)
        assertEquals((108 shl 8) or 1, attr(vector, "height").data)
        assertEquals(ValueType.FLOAT, attr(vector, "viewportWidth").valueType)
        assertEquals(108f, java.lang.Float.intBitsToFloat(attr(vector, "viewportHeight").data), 0f)
        val paths = kids(vector, "path")
        assertEquals(2, paths.size)
        assertEquals(ring, attr(paths[0], "pathData").valueAsString)
        assertEquals(ValueType.COLOR_ARGB8, attr(paths[0], "fillColor").valueType)
        assertEquals(0xFF4F5BD5.toInt(), attr(paths[0], "fillColor").data)
        assertEquals(0xFFFFFFFF.toInt(), attr(paths[0], "strokeColor").data)
        assertEquals(ValueType.FLOAT, attr(paths[0], "strokeWidth").valueType)
        assertEquals(1.5f, java.lang.Float.intBitsToFloat(attr(paths[0], "strokeWidth").data), 0f)
        assertEquals(1f, java.lang.Float.intBitsToFloat(attr(paths[1], "strokeWidth").data), 0f)
        assertEquals(ValueType.DEC, attr(paths[1], "strokeLineCap").valueType)
        assertEquals(1, attr(paths[1], "strokeLineCap").data)
        assertEquals(2, attr(paths[1], "strokeLineJoin").data)
        assertEquals(null, paths[1].searchAttributeByResourceId(id("fillColor")))
        // the vector attributes live in the android namespace
        assertEquals("http://schemas.android.com/apk/res/android", attr(vector, "width").uri)
        // monochrome has the outline-only badge
        val mono = kids(kids(root, "monochrome").single(), "layer-list").single()
        val monoPath = kids(kids(kids(mono, "item")[1], "vector").single(), "path").single()
        assertEquals(null, monoPath.searchAttributeByResourceId(id("fillColor")))
    }

    @Test fun inlineChildMovesIntoTheFirstItem() {
        val zip = apk("res/Ab.xml" to icon("a", inlineForeground = true))
        val c = ctx(zip)
        op("1", layers = """{"foreground": [{"pathData": "M0,0"}]}""").apply(c)
        val fg = kids(parse(c.replacedEntries.getValue("res/Ab.xml")).documentElement, "foreground").single()
        assertEquals(0, fg.attributeCount)
        val items = kids(kids(fg, "layer-list").single(), "item")
        assertEquals(2, items.size)
        assertEquals(0, items[0].attributeCount)
        val inset = kids(items[0], "inset").single()
        assertEquals(0x7f080001, attr(inset, "drawable").data)
        assertEquals(ValueType.DIMENSION, attr(inset, "insetLeft").valueType)
        assertEquals((18 shl 8) or 1, attr(inset, "insetLeft").data)
        assertEquals(1, kids(items[1], "vector").size)
    }

    @Test fun missingLayerIsSkippedAndNoMatchFailsExpect() {
        val zip = apk("res/Ab.xml" to icon("a"))
        val c = ctx(zip)
        // no monochrome in the file: only the foreground is rewritten, one changed file
        assertEquals(listOf("res/Ab.xml :: foreground"), op("1").apply(c))
        assertEquals(0, kids(parse(c.replacedEntries.getValue("res/Ab.xml")).documentElement, "monochrome").size)
        try {
            op("2").apply(ctx(zip))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "expected 2" in e.message!!)
        }
        try {
            op(layers = """{"monochrome": [{"pathData": "M0,0"}]}""").apply(ctx(zip))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "matched nothing" in e.message!!)
        }
    }

    @Test fun alreadyPatchedFailsLoudly() {
        val c = ctx(apk("res/Ab.xml" to icon("a")))
        op().apply(c)
        val again = ctx(apk("res/Ab.xml" to c.replacedEntries.getValue("res/Ab.xml")))
        try {
            op().apply(again)
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "already carries this badge" in e.message!!)
        }
    }

    @Test fun layerWithoutDrawableOrSingleChildFails() {
        val doc = ResXmlDocument()
        val root = doc.newElement("adaptive-icon")
        root.newElement("foreground")
        doc.refreshFull()
        try {
            op().apply(ctx(apk("res/Ab.xml" to doc.bytes)))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "exactly one child" in e.message!!)
        }
    }

    @Test fun verifyAppliedCountsPatchedIcons() {
        val before = apk("res/Ab.xml" to icon("a", monochrome = "m"), "res/Cd.xml" to icon("a"))
        val c = ctx(before)
        val o = op("2")
        o.apply(c)
        try {
            o.verifyApplied(ctx(before))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "has no badge" in e.message!!)
        }
        val after = apk(
            "res/Ab.xml" to c.replacedEntries.getValue("res/Ab.xml"),
            "res/Cd.xml" to c.replacedEntries.getValue("res/Cd.xml"),
        )
        assertEquals(2, o.verifyApplied(ctx(after)).size)
        try {
            op("3").verifyApplied(ctx(after))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(e.message!!, "does not carry this patch" in e.message!!)
        }
    }

    @Test fun argumentsAreValidated() {
        fun bad(layers: String) = try {
            op(layers = layers); fail(layers)
        } catch (e: OpArgError) {
        }
        bad("""{}""")
        bad("""{"background": [{"pathData": "M0,0"}]}""")
        bad("""{"foreground": []}""")
        bad("""{"foreground": [{"fillColor": "#FF000000"}]}""")
        bad("""{"foreground": [{"pathData": "M0,0", "fillColor": "#000000"}]}""")
        bad("""{"foreground": [{"pathData": "M0,0", "strokeLineCap": "flat"}]}""")
        bad("""{"foreground": [{"pathData": "M0,0", "strokeLineJoin": "x"}]}""")
        bad("""{"foreground": [{"pathData": "M0,0", "strokeWidth": "1"}]}""")
        bad("""{"foreground": [{"pathData": "M0,0", "opacity": 1}]}""")
    }
}
