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

    fun path(rel: String): String =
        rel.split('/').joinToString("/") { if (it == PLACEHOLDER) prefixPath else it }

    /** Stages `relative path -> smali text`; two inputs mapping to one path are an error. */
    fun stage(files: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rel, text) in files) {
            val p = path(rel)
            if (out.put(p, text(text)) != null) throw PatchFailure("staging: two files map to $p")
        }
        return out
    }

    companion object {
        const val PLACEHOLDER = "shminterop"
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
