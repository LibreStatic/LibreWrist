package io.openshm.patcher.engine

/** Error of an op whose anchor is missing, ambiguous or has the wrong count (never ignored). */
class PatchFailure(message: String) : RuntimeException(message)

/** Unknown op, or an unknown / missing / ill-typed argument. */
class OpArgError(message: String) : IllegalArgumentException(message)

/** Text helpers over baksmali output; ports of the helpers of superpatcher/engine.py. */
internal object SmaliText {
    private val LABEL = Regex("(?<=[\\s\\{]):[a-z]\\w*")

    /** [s] without its blank lines (disassembly puts one between instructions, hand-written bodies don't). */
    fun dropBlankLines(s: String): String = s.lines().filter { it.isNotBlank() }.joinToString("\n")

    private val COMMENT_LINE = Regex("(?m)^[ \\t]*#[^\\n]*\\n")

    /**
     * What survives assembling and disassembling again (see [CanonicalLabelsTree]): comment lines
     * are gone and every label becomes `:L`.
     */
    fun canonLabels(s: String): String = LABEL.replace(COMMENT_LINE.replace(s, ""), ":L")

    const val END_METHOD = "\n.end method"

    /** Python's `str.count` (non-overlapping). */
    fun count(src: String, needle: String): Int {
        if (needle.isEmpty()) return src.length + 1
        var n = 0
        var i = src.indexOf(needle)
        while (i >= 0) {
            n++
            i = src.indexOf(needle, i + needle.length)
        }
        return n
    }

    /** Python's `str.replace(old, new, 1)`. */
    fun replaceFirst(src: String, old: String, new: String): String {
        val i = src.indexOf(old)
        return if (i < 0) src else src.substring(0, i) + new + src.substring(i + old.length)
    }

    /** Span (start, end) of the first method whose header is `.method <sig>` (sig is literal). */
    fun methodSpan(src: String, sig: String): IntArray? {
        val m = PyRegex.search("""\.method\s+""" + PyRegex.escape(sig) + """\n""", src) ?: return null
        return spanFrom(src, m.start())
    }

    fun spanFrom(src: String, start: Int): IntArray {
        val end = src.indexOf(END_METHOD, start)
        if (end < 0) throw PatchFailure("method at offset $start has no .end method")
        return intArrayOf(start, end + END_METHOD.length)
    }

    private val METHODS = PyRegex.compile("""\.method\s+([^\n]+)\n(.*?)(?=\n\.end method)""", dotAll = true)

    /** (signature, body) of every method; quirks of the Python regex included. */
    fun iterMethods(src: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val m = METHODS.matcher(src)
        while (m.find()) out.add(m.group(1) to m.group(2))
        return out
    }

    /**
     * Inserts [insert] after the `.locals` line of the first method with header [sig], raising
     * `.locals` to at least [needRegs]. Returns the new text and whether the method was found.
     * Already applied (the insert follows the `.locals` line) counts as found and changes nothing.
     */
    fun insertAtMethodStart(src: String, sig: String, insert: String, needRegs: Int): Pair<String, Boolean> {
        val pat = PyRegex.compile("""(\.method\s+""" + PyRegex.escape(sig) + """\n)(\s*\.locals (\d+)\n)""")
        val m = pat.matcher(src)
        if (!m.find()) return src to false
        val bodyAfter = src.substring(m.end(), minOf(src.length, m.end() + insert.length))
        if (bodyAfter == insert) return src to true
        val locals = maxOf(m.group(3).toInt(), needRegs)
        return (src.substring(0, m.start()) + m.group(1) + "    .locals $locals\n" + insert +
            src.substring(m.end())) to true
    }

    /**
     * True if the method [sig] of [src] directly follows its `.locals` line with [insert].
     * With [afterDirectives] the insert may also follow the method's `.param` / `.annotation`
     * blocks: disassembly prints those before the code, wherever the insert was written.
     */
    fun startsWithInsert(src: String, sig: String, insert: String, afterDirectives: Boolean = false): Boolean {
        val m = PyRegex.search("""(\.method\s+""" + PyRegex.escape(sig) + """\n)(\s*\.locals (\d+)\n)""", src)
            ?: return false
        var i = m.end()
        while (true) {
            if (src.startsWith(insert, i)) return true
            if (!afterDirectives) return false
            val eol = src.indexOf('\n', i)
            if (eol < 0) return false
            val line = src.substring(i, eol)
            // blank, a directive of the method header (4 spaces + '.'), or inside one (8+ spaces)
            val skippable = line.isBlank() || line.startsWith("    .") && !line.startsWith("    .line") ||
                line.startsWith("        ")
            if (!skippable || line.startsWith("    .end method")) return false
            i = eol + 1
        }
    }

    /** `Python's re.sub(pattern, fn, text)` with a callback. */
    fun sub(pattern: java.util.regex.Pattern, text: String, fn: (java.util.regex.MatchResult) -> String): String {
        val m = pattern.matcher(text)
        val sb = StringBuilder()
        var last = 0
        while (m.find()) {
            sb.append(text, last, m.start()).append(fn(m))
            last = m.end()
        }
        return sb.append(text, last, text.length).toString()
    }
}
