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

    /** Every base `values/strings*.xml`, found by glob so split files are covered without listing them. */
    private fun baseFiles(): List<File> =
        File(res, "values").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("strings") && it.name.endsWith(".xml") }
            .sortedBy { it.name }

    /** Locale directories: the `values-*` ones that carry a `strings.xml` (so `values-night` is not one). */
    private fun localeDirs(): List<File> =
        res.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .sortedBy { it.name }

    /** Every translated file, which must exist for each base file in each locale. */
    private fun translations(): List<File> =
        baseFiles().flatMap { base -> localeDirs().map { File(it, base.name) } }.filter { it.exists() }

    @Test fun everyBaseFileHasAllTranslations() {
        val missing = baseFiles().flatMap { base ->
            localeDirs().filter { !File(it, base.name).exists() }.map { "${it.name}/${base.name} is missing" }
        }
        if (missing.isNotEmpty()) fail(missing.joinToString("\n"))
    }

    @Test fun translationsMatchTheEnglishStrings() {
        val problems = mutableListOf<String>()
        for (base in baseFiles()) {
            val english = load(base)
            for (dir in localeDirs()) {
                val file = File(dir, base.name)
                if (!file.exists()) continue
                checkTranslation(english, load(file), "${dir.name.removePrefix("values-")}/${base.name}", problems)
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    private fun checkTranslation(english: Strings, t: Strings, locale: String, problems: MutableList<String>) {
        run {
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
    }

    @Test fun apostrophesAreEscaped() {
        val unescaped = Regex("""(?<!\\)'""")
        val problems = (baseFiles() + translations()).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val text = line.substringAfter('>', "").substringBeforeLast('<', "")
                if (unescaped.containsMatchIn(text)) "${file.parentFile.name}/${file.name}:${index + 1}: $line" else null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
