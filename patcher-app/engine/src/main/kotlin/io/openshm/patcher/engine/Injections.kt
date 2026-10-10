package io.openshm.patcher.engine

import java.io.File

/**
 * Rewrites the package placeholder of our own code (`shminterop`) to the real package prefix, as
 * `scripts/lib/stage_prefix.py` does for the Python build: in text, `shminterop/` becomes the
 * prefix as a path and `shminterop` the prefix as a name; a path component named `shminterop`
 * becomes the prefix's components.
 */
class PrefixStager(val prefix: String) {
    init {
        requireValidPrefix(prefix)
    }

    private val prefixPath = prefix.replace('.', '/')

    fun text(s: String): String = s.replace("$PLACEHOLDER/", "$prefixPath/").replace(PLACEHOLDER, prefix)

    /**
     * [text] for the smali of a compiled class, plus the String-switch keys: javac and d8 compile
     * `switch (string)` to a `sparse-switch` over `hashCode()`, and the keys are hashes of the
     * placeholder strings, so renaming a case string leaves a stale key. Here every
     * `.sparse-switch` entry whose key is the hash of the first `const-string` after its label
     * gets the hash of the staged string, and the table is re-sorted (keys must ascend). A table
     * where only some entries fit (an int switch in a method that also hashes strings) fails, as
     * does a new hash collision; one where none fit is left alone, unless one of its keys is the
     * hash of a renamed string (see [requireNoStaleKeys]). Only the first string of a label is
     * looked at, so colliding strings in one label are not re-verified.
     *
     * d8 also names desugared lambdas after the class (`lambda$run$0$shminterop-shmbridge-X`, the
     * dots of the package turned into dashes), so `shminterop-` becomes the prefix with dashes.
     */
    fun smali(original: String): String {
        val staged = text(original.replace("$PLACEHOLDER-", prefix.replace('.', '-') + "-"))
        if (staged == original || ".sparse-switch" !in staged) return staged
        val old = original.split('\n')
        val new = staged.split('\n').toMutableList()
        if (old.size != new.size) throw PatchFailure("staging: renaming changed the line count of a class")
        var i = 0
        while (i < new.size) {
            if (new[i].trim() != ".sparse-switch") { i++; continue }
            var end = i + 1
            while (end < new.size && new[end].trim() != ".end sparse-switch") end++
            if (end >= new.size) throw PatchFailure("staging: unterminated .sparse-switch")
            rehash(old, new, i + 1, end)
            i = end + 1
        }
        requireNoStaleKeys(old, new)
        return new.joinToString("\n")
    }

    /**
     * Independent of how the switch is laid out: no `sparse-switch` key may still be the hash of
     * a string literal that the rename changed. Catches a String switch [rehash] did not recognise
     * (for example after a d8 code-shape change) instead of shipping keys that never match.
     */
    private fun requireNoStaleKeys(old: List<String>, new: List<String>) {
        val stale = HashSet<Int>()
        for (n in old.indices) {
            val o = CONST_STRING_RE.matchEntire(old[n]) ?: continue
            if (old[n] == new[n]) continue
            unescape(o.groupValues[1])?.let { stale.add(it.hashCode()) }
        }
        if (stale.isEmpty()) return
        var inSwitch = false
        for (line in new) {
            when (line.trim()) {
                ".sparse-switch" -> { inSwitch = true; continue }
                ".end sparse-switch" -> { inSwitch = false; continue }
            }
            if (!inSwitch) continue
            val m = ENTRY_RE.matchEntire(line) ?: continue
            if (parseInt(m.groupValues[1]) in stale) {
                throw PatchFailure("staging: a sparse-switch key is still the hash of a renamed string ('${line.trim()}')")
            }
        }
    }

    private fun rehash(old: List<String>, new: MutableList<String>, from: Int, to: Int) {
        class Entry(val label: String, val key: Int, val newKey: Int?)
        val entries = ArrayList<Entry>()
        var matched = 0
        for (n in from until to) {
            val m = ENTRY_RE.matchEntire(new[n]) ?: throw PatchFailure("staging: unexpected sparse-switch line '${new[n].trim()}'")
            val key = parseInt(m.groupValues[1])
            val label = m.groupValues[2]
            val at = new.indexOfFirst { it.trim() == ":$label" }
            var newKey: Int? = null
            if (at >= 0) {
                val c = (at + 1 until minOf(new.size, at + 12)).firstOrNull { CONST_STRING_RE.matches(new[it]) }
                if (c != null) {
                    val o = unescape(CONST_STRING_RE.matchEntire(old[c])!!.groupValues[1])
                    if (o != null && o.hashCode() == key) {
                        newKey = unescape(CONST_STRING_RE.matchEntire(new[c])!!.groupValues[1])?.hashCode()
                            ?: throw PatchFailure("staging: cannot read the string literal '${new[c].trim()}'")
                        matched++
                    }
                }
            }
            entries.add(Entry(label, key, newKey))
        }
        if (matched == 0) return
        if (matched != entries.size) throw PatchFailure("staging: a sparse-switch mixes String hashes and other keys")
        val sorted = entries.sortedBy { it.newKey!! }
        if (sorted.map { it.newKey }.toSet().size != sorted.size) throw PatchFailure("staging: renaming creates a String hash collision in a switch")
        for ((k, e) in sorted.withIndex()) new[from + k] = "        ${hex(e.newKey!!)} -> :${e.label}"
    }

    private fun parseInt(s: String): Int {
        val neg = s.startsWith("-")
        val v = java.lang.Long.parseLong(s.removePrefix("-").removePrefix("0x"), 16)
        return (if (neg) -v else v).toInt()
    }

    private fun hex(v: Int): String = if (v < 0) "-0x" + java.lang.Long.toHexString(-(v.toLong())) else "0x" + Integer.toHexString(v)

    /** The value of a smali string literal body, or null on an escape we do not know. */
    private fun unescape(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i++]
            if (c != '\\') { sb.append(c); continue }
            if (i >= s.length) return null
            when (val e = s[i++]) {
                'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b')
                'f' -> sb.append('\u000c'); '\\', '"', '\'' -> sb.append(e)
                'u' -> {
                    if (i + 4 > s.length) return null
                    sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                    i += 4
                }
                else -> return null
            }
        }
        return sb.toString()
    }

    fun path(rel: String): String =
        rel.split('/').joinToString("/") { if (it == PLACEHOLDER) prefixPath else it }

    /**
     * Stages `relative path -> smali text`; two inputs mapping to one path are an error. With
     * [compiled] the texts are disassembled compiled classes and go through [smali].
     */
    fun stage(files: Map<String, String>, compiled: Boolean = false): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rel, text) in files) {
            val p = path(rel)
            if (out.put(p, if (compiled) smali(text) else text(text)) != null) throw PatchFailure("staging: two files map to $p")
        }
        return out
    }

    companion object {
        const val PLACEHOLDER = "shminterop"
        private val ENTRY_RE = Regex("""^\s+(-?0x[0-9a-fA-F]+) -> :(\w+)$""")
        private val CONST_STRING_RE = Regex("""^\s+const-string(?:/jumbo)? \S+, "(.*)"$""")
        private val PREFIX_RE = Regex("^[A-Za-z]\\w*(\\.[A-Za-z]\\w*)*$")

        /** Throws unless [prefix] is a plain Java package name (ASCII letters, digits, `_`, dots). */
        fun requireValidPrefix(prefix: String) {
            if (!PREFIX_RE.matches(prefix)) throw PatchFailure("invalid package prefix '$prefix'")
        }
    }
}

/** Smali classes read from a directory tree (`com/x/Y.smali` below [dir]), taken as they are. */
class DirectoryInjection(private val dir: File) : InjectionSource {
    override fun classes(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (!rel.endsWith(".smali")) throw PatchFailure("injection: $rel is not a smali file")
            out[rel] = f.readText(Charsets.UTF_8)
        }
        if (out.isEmpty()) throw PatchFailure("injection: no smali files in ${dir.name}")
        return out
    }
}

/** Placeholder-form smali (`relative path -> text`) staged to the real package prefix on use. */
class StagedInjection(private val files: Map<String, String>, prefix: String) : InjectionSource {
    private val stager = PrefixStager(prefix)
    override fun classes(): Map<String, String> = stager.stage(files)
}
