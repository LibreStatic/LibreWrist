package io.openshm.patcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** `strings_key_backup.xml` exists for every locale with the English names, format arguments and escaped apostrophes. */
class KeyBackupStringsTest {
    private val res = File("src/main/res")

    private fun load(file: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun everyLocaleHasTheSameStrings() {
        val english = load(File(res, "values/strings_key_backup.xml"))
        val locales = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        assertTrue(locales.size >= 17)
        for (dir in locales) {
            val file = File(dir, "strings_key_backup.xml")
            assertTrue("${dir.name} lacks strings_key_backup.xml", file.exists())
            val t = load(file)
            assertEquals(dir.name, english.keys, t.keys)
            for ((name, text) in t) {
                assertEquals("${dir.name}:$name", english.getValue(name).contains("%1\$s"), text.contains("%1\$s"))
                assertTrue("${dir.name}:$name has an unescaped apostrophe", !File(file.path).readText().contains(Regex("""(?<!\\)'""")))
            }
        }
    }
}
