package io.openshm.patcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles regexes with ICU, which rejects a bare literal `}` (and `{`) that the JDK accepts.
 * These JVM tests pin the translation so every literal brace comes out escaped.
 */
class PyRegexTest {
    /** True when every brace of [regex] is escaped or belongs to a `{m}` / `{m,}` / `{m,n}` quantifier. */
    private fun bracesAreIcuSafe(regex: String): Boolean {
        var i = 0
        while (i < regex.length) {
            val c = regex[i]
            when {
                c == '\\' -> i++
                c == '{' -> {
                    val m = Regex("""\{\d+(,\d*)?\}""").matchAt(regex, i) ?: return false
                    i += m.value.length - 1
                }
                c == '}' -> return false
            }
            i++
        }
        return true
    }

    @Test fun literalBracesAreEscaped() {
        assertEquals("a\\}b", PyRegex.translate("a}b"))
        assertEquals("\\{x\\}", PyRegex.translate("{x}"))
        assertEquals("\\$\\{PKG\\}", PyRegex.translate("\\$\\{PKG\\}"))
    }

    @Test fun quantifiersAreKeptWhole() {
        assertEquals("a{2}b{1,}c{0,3}", PyRegex.translate("a{2}b{1,}c{0,3}"))
    }

    @Test fun bracesInsideAClassAreEscaped() {
        assertEquals("[\\{\\}a]", PyRegex.translate("[{}a]"))
    }

    @Test fun escapedLiteralsCompileAndMatch() {
        for (literal in listOf("{", "}", "a{b}c", "\${PKG_PREFIX}", "{{1,2}}", "x{,3}")) {
            val translated = PyRegex.translate(PyRegex.escape(literal))
            assertTrue(translated, bracesAreIcuSafe(translated))
            assertTrue(literal, PyRegex.compile(PyRegex.escape(literal)).matcher(literal).matches())
        }
    }

    @Test fun mixedPatternsAreIcuSafe() {
        for (py in listOf("v{1,2}}", "{not a quantifier}", "[^}]*}", "(?P<n>\\w{3})\\}", "a{x,2}")) {
            assertTrue(py, bracesAreIcuSafe(PyRegex.translate(py)))
        }
    }
}
