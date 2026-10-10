package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BinaryManifestTest {
    private fun emptyManifest(): ByteArray {
        val b = AndroidManifestBlock.empty()
        b.packageName = "com.example.app"
        b.getOrCreateApplicationElement()
        b.refresh()
        return b.bytes
    }

    private fun arsc(): ByteArray {
        val t = TableBlock()
        val p = t.newPackage(0x7f, "com.example.app")
        p.getOrCreate("", "string", "app_name").setValueAsString("App")
        t.refresh()
        return t.bytes
    }

    private fun reload(m: BinaryManifest, table: () -> TableBlock? = { null }) = BinaryManifest(m.toBytes(), table)

    private fun app(m: ManifestDocument) = m.root.children.first { it.tag == "application" }

    @Test fun typedValuesRoundTrip() {
        val res = ArscResourceTable(arsc())
        val m = BinaryManifest(emptyManifest()) { res.table }
        val activity = app(m).addChild(
            "activity",
            listOf(
                "android:name" to "com.example.app.Main",
                "android:exported" to "true",
                "android:theme" to "@android:style/Theme.NoDisplay",
                "android:label" to "@string/app_name",
            ),
        )
        m.root.addChild("permission", listOf("android:name" to "com.example.app.P", "android:protectionLevel" to "signature"))

        val back = reload(m) { res.table }
        val a = app(back).children.single()
        assertEquals("activity", a.tag)
        assertEquals("true", a.attr("android:exported"))
        assertEquals("@android:style/Theme.NoDisplay", a.attr("android:theme"))
        assertEquals("@string/app_name", a.attr("android:label"))
        assertEquals("com.example.app.Main", a.attr("android:name"))
        assertEquals("signature", back.root.children.first { it.tag == "permission" }.attr("android:protectionLevel"))
        assertEquals("com.example.app", back.packageName)
        // framework attributes come out sorted by resource id (aapt2 order), the plain ones last
        assertEquals(
            listOf("android:theme", "android:label", "android:name", "android:exported"),
            a.attributes.map { it.first },
        )
        assertTrue(activity.attr("android:name") != null)
    }

    @Test fun setReplaceRemoveAndPlainAttributes() {
        val m = BinaryManifest(emptyManifest()) { null }
        val application = app(m)
        application.setAttr("android:label", "Plain")
        application.setAttr("foo", "bar")
        assertEquals("Plain", application.attr("android:label"))
        application.setAttr("android:label", "Other")
        assertEquals(listOf("android:label" to "Other", "foo" to "bar"), application.attributes)
        assertTrue(application.removeAttr("foo"))
        assertFalse(application.removeAttr("foo"))
        assertNull(application.attr("foo"))
        assertEquals("Other", app(reload(m)).attr("android:label"))
    }

    @Test fun addChildAtIndexAndRemove() {
        val m = BinaryManifest(emptyManifest()) { null }
        m.root.addChild("uses-permission", listOf("android:name" to "b"))
        m.root.addChild("uses-permission", listOf("android:name" to "a"), 0)
        assertEquals(listOf("a", "b"), m.root.children.filter { it.tag == "uses-permission" }.map { it.attr("android:name") })
        m.root.children.first { it.tag == "uses-permission" }.remove()
        val back = reload(m)
        assertEquals(listOf("b"), back.root.children.filter { it.tag == "uses-permission" }.map { it.attr("android:name") })
    }

    @Test fun elementWrappersHaveIdentity() {
        val m = BinaryManifest(emptyManifest()) { null }
        assertEquals(app(m), app(m))
        assertEquals(app(m).hashCode(), app(m).hashCode())
        assertEquals(m.root, app(m).parent)
    }

    @Test fun unencodableValuesFailClosedAndLeaveNothingBehind() {
        val m = BinaryManifest(emptyManifest()) { null }
        val application = app(m)
        for ((k, v) in listOf(
            "android:launchMode" to "singleTop",       // not a string attribute, unknown to the encoder
            "android:noSuchAttribute" to "x",          // not a framework attribute
            "android:theme" to "@android:style/NoSuch", // unknown framework resource
            "android:label" to "@string/missing",       // unknown app resource (no table)
            "android:protectionLevel" to "magic",
            "other:thing" to "x",
        )) {
            try {
                application.setAttr(k, v)
                fail("$k=$v")
            } catch (e: PatchFailure) {
                // expected
            }
            assertNull("$k left behind", application.attr(k))
        }
        try {
            application.addChild("activity", listOf("android:name" to "x", "android:launchMode" to "singleTop"))
            fail()
        } catch (e: PatchFailure) {
            assertTrue(application.children.isEmpty())
        }
    }

    @Test fun arscStringsPerLocale() {
        val res = ArscResourceTable(arsc())
        assertTrue(res.hasString("app_name"))
        assertFalse(res.hasString("interop_app_label"))
        assertTrue(res.hasStringsFile(""))
        assertFalse(res.hasStringsFile("-es"))
        res.addString("", "interop_app_label", "Phone alarms")
        res.addString("-es", "interop_app_label", "Alarmas del teléfono")
        val back = ArscResourceTable(res.toBytes())
        assertEquals("Phone alarms", back.string("", "interop_app_label"))
        assertEquals("Alarmas del teléfono", back.string("-es", "interop_app_label"))
        assertTrue(back.hasStringsFile("-es"))
        assertEquals("App", back.string("", "app_name"))
    }
}
