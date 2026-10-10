package io.openshm.patcher.engine

/**
 * Minimal strict JSON reader (the engine must not depend on `org.json`, which is Android-only).
 *
 * Values map to: `Map<String, Any?>` (insertion ordered), `List<Any?>`, `String`, `Long`, `Double`,
 * `Boolean` and `null`. Stricter than Python's `json`: duplicate object keys, `NaN`/`Infinity`,
 * integers beyond 64 bits, control characters in strings and trailing content are errors.
 */
object Json {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) p.fail("trailing content")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw ParseException("JSON: $msg at offset $pos")

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\t' || s[pos] == '\r')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > 64) fail("nesting too deep")
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') fail("object key expected")
                val k = str()
                if (m.containsKey(k)) fail("duplicate key \"$k\"")
                skipWs()
                if (pos >= s.length || s[pos] != ':') fail("':' expected")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) fail("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("',' or '}' expected")
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) fail("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("',' or ']' expected")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) fail("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail("bad \\u escape")
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) fail("bad number")
            if (s[pos] == '0') pos++
            else if (s[pos] in '1'..'9') while (pos < s.length && s[pos] in '0'..'9') pos++
            else fail("bad number")
            var isFloat = false
            if (pos < s.length && s[pos] == '.') {
                isFloat = true
                pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) fail("bad number")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) fail("bad number")
            }
            val t = s.substring(start, pos)
            return if (isFloat) t.toDouble() else t.toLongOrNull() ?: fail("integer out of range")
        }
    }
}
