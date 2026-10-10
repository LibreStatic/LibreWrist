package io.openshm.patcher.engine

import java.util.regex.Pattern

/**
 * The catalogue's regexes are written in Python `re` syntax restricted to the portable subset
 * (see superpatcher/engine.py). This translates that subset to `java.util.regex`:
 * `(?P<n>...)` / `(?P=n)` become Java named groups, a literal `{` or `}` that is not part of a
 * quantifier is escaped (Android's ICU regex engine requires it), and `.` / `$` only consider `\n` (UNIX_LINES), like Python.
 */
internal object PyRegex {
    private val cache = object : LinkedHashMap<String, Pattern>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?) = size > 256
    }

    /** Names of the named groups `(?P<name>` of [pattern], in order. */
    fun groupNames(pattern: String): List<String> =
        Regex("""\(\?P<([A-Za-z_]\w*)>""").findAll(pattern).map { it.groupValues[1] }.toList()

    fun javaGroupName(name: String) = "g" + name.replace("_", "XuX")

    /** Python 3.7+ `re.escape`: backslash before the regex-special characters only. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c in SPECIAL) sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    private const val SPECIAL = "()[]{}?*+-|^$\\.&~# \t\n\r\u000b\u000c"
    private val QUANTIFIER = Regex("""\{\d+(,\d*)?\}""")

    fun compile(pattern: String, dotAll: Boolean = false): Pattern {
        val key = (if (dotAll) "S:" else "N:") + pattern
        synchronized(cache) { cache[key]?.let { return it } }
        val p = try {
            Pattern.compile(translate(pattern), Pattern.UNIX_LINES or (if (dotAll) Pattern.DOTALL else 0))
        } catch (e: IllegalArgumentException) {
            throw OpArgError("invalid regex ${pattern.take(80)}: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        synchronized(cache) { cache[key] = p }
        return p
    }

    internal fun translate(py: String): String {
        val out = StringBuilder(py.length + 16)
        var i = 0
        var inClass = false
        while (i < py.length) {
            val c = py[i]
            when {
                c == '\\' -> {
                    out.append(c)
                    if (i + 1 < py.length) out.append(py[i + 1])
                    i += 2
                    continue
                }
                inClass -> {
                    if (c == ']') inClass = false
                    // A literal '[' (or '&&') inside a class has a special meaning in Java only; Android's
                    // ICU engine also rejects bare braces there, and an escaped brace is valid everywhere.
                    if (c == '[' || c == '{' || c == '}') out.append('\\')
                    else if (c == '&' && py.startsWith("&&", i)) out.append('\\')
                    out.append(c)
                }
                c == '[' -> {
                    inClass = true
                    out.append(c)
                    i++
                    if (i < py.length && py[i] == '^') { out.append('^'); i++ }
                    if (i < py.length && py[i] == ']') { out.append("\\]"); i++ }
                    continue
                }
                c == '(' && py.startsWith("(?P<", i) -> {
                    val end = py.indexOf('>', i)
                    if (end < 0) throw OpArgError("unterminated group name in ${py.take(80)}")
                    out.append("(?<").append(javaGroupName(py.substring(i + 4, end))).append('>')
                    i = end + 1
                    continue
                }
                c == '(' && py.startsWith("(?P=", i) -> {
                    val end = py.indexOf(')', i)
                    if (end < 0) throw OpArgError("unterminated group reference in ${py.take(80)}")
                    out.append("\\k<").append(javaGroupName(py.substring(i + 4, end))).append('>')
                    i = end + 1
                    continue
                }
                c == '{' -> {
                    // A quantifier is copied whole; any other brace is literal. Android's ICU engine
                    // rejects an unescaped literal '}' (the JDK accepts it), so both braces are escaped.
                    val quantifier = QUANTIFIER.matchAt(py, i)
                    if (quantifier != null) {
                        out.append(quantifier.value)
                        i += quantifier.value.length
                        continue
                    }
                    out.append("\\{")
                }
                c == '}' -> out.append("\\}")
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** `re.search(...)`-style helper. */
    fun search(pattern: String, text: String): java.util.regex.Matcher? {
        val m = compile(pattern).matcher(text)
        return if (m.find()) m else null
    }
}
