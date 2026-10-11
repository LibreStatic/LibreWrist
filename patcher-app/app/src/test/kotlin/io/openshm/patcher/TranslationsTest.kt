package io.openshm.patcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translation must match the English strings: the same names, the same format arguments
 * (`%1$s`, `%d`, ...) in every string and plural item, no untranslatable string translated, and no
 * unescaped apostrophe (aapt rejects it at build time only in some cases; it silently cuts text in
 * others).
 */
class TranslationsTest {
    private val res = File("src/main/res")

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val fixed: Set<String>)

    private fun load(file: File): Strings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val fixed = HashSet<String>()
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val node = root.childNodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> {
                    strings[name] = node.textContent
                    if (node.getAttribute("translatable") == "false") fixed += name
                }
                "plurals" -> {
                    val items = LinkedHashMap<String, String>()
                    val children = node.getElementsByTagName("item")
                    for (j in 0 until children.length) {
                        val item = children.item(j) as Element
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[name] = items
                }
            }
        }
        return Strings(strings, plurals, fixed)
    }

    private val formatArg = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc%]""")

    private fun args(text: String): List<String> =
        formatArg.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    private fun translations(): List<File> =
        res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { File(it, "strings.xml") }

    @Test fun translationsMatchTheEnglishStrings() {
        val english = load(File(res, "values/strings.xml"))
        val problems = mutableListOf<String>()
        for (file in translations()) {
            val locale = file.parentFile.name.removePrefix("values-")
            val t = load(file)
            val expected = english.strings.keys - english.fixed
            (expected - t.strings.keys).forEach { problems += "$locale: missing string $it" }
            (t.strings.keys - english.strings.keys).forEach { problems += "$locale: unknown string $it" }
            (t.strings.keys intersect english.fixed).forEach { problems += "$locale: $it is not translatable" }
            (english.plurals.keys - t.plurals.keys).forEach { problems += "$locale: missing plurals $it" }
            (t.plurals.keys - english.plurals.keys).forEach { problems += "$locale: unknown plurals $it" }
            for ((name, text) in t.strings) {
                val source = english.strings[name] ?: continue
                if (args(text) != args(source)) problems += "$locale: $name has ${args(text)}, English has ${args(source)}"
            }
            for ((name, items) in t.plurals) {
                val source = english.plurals[name] ?: continue
                val sourceArgs = args(source["other"].orEmpty())
                if ("other" !in items) problems += "$locale: plurals $name has no 'other' item"
                for ((quantity, text) in items) {
                    // Languages may drop the number in a "one"/"zero"/"two" form ("an app"), never add one.
                    val found = args(text)
                    val ok = if (quantity == "other") found == sourceArgs else sourceArgs.containsAll(found)
                    if (!ok) problems += "$locale: plurals $name[$quantity] has $found, English has $sourceArgs"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test fun apostrophesAreEscaped() {
        val unescaped = Regex("""(?<!\\)'""")
        val problems = (listOf(File(res, "values/strings.xml")) + translations()).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val text = line.substringAfter('>', "").substringBeforeLast('<', "")
                if (unescaped.containsMatchIn(text)) "${file.parentFile.name}:${index + 1}: $line" else null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
