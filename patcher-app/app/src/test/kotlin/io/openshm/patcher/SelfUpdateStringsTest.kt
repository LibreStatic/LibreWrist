package io.openshm.patcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** `strings_self_update.xml` exists for every locale with the English names, format arguments and escaped apostrophes. */
class SelfUpdateStringsTest {
    private val res = File("src/main/res")
    private val formatArg = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")

    private fun load(file: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun everyLocaleHasTheSameStringsAndArguments() {
        val english = load(File(res, "values/strings_self_update.xml"))
        val locales = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        assertTrue(locales.size >= 17)
        for (dir in locales) {
            val file = File(dir, "strings_self_update.xml")
            assertTrue("${dir.name} lacks strings_self_update.xml", file.exists())
            val t = load(file)
            assertEquals(dir.name, english.keys, t.keys)
            for ((name, text) in t) {
                assertEquals("${dir.name}:$name", formatArg.findAll(english.getValue(name)).map { it.value }.sorted().toList(), formatArg.findAll(text).map { it.value }.sorted().toList())
            }
            assertTrue("${dir.name} has an unescaped apostrophe", !file.readText().contains(Regex("""(?<!\\)'""")))
        }
    }
}
