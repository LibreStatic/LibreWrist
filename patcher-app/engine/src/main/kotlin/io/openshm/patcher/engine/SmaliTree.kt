package io.openshm.patcher.engine

/** Cheap pre-selection of the classes an op needs to look at; see [SmaliTree.candidates]. */
class ClassFilter(
    /** `re.search`ed (Python semantics) against the relative path, e.g. `smali_classes2/com/x/Y.smali`. */
    val pathRegex: String? = null,
    /** A path matching any of these (`re.search`) is skipped. */
    val excludePathRegexes: List<String> = emptyList(),
    /** Texts that must all occur in the class's smali; only an optimisation hint, ops re-check. */
    val requireAll: List<String> = emptyList(),
    /** At least one of these must occur in the class's smali (hint, ops re-check). */
    val requireAny: List<String> = emptyList(),
)

/**
 * The smali view of an APK's dex files, as `apktool d` would lay it out: one `smali` /
 * `smali_classesN` directory per dex and one `<type>.smali` file per class, in the text format
 * baksmali prints. The ops work on this view only; they never see dex structures.
 */
interface SmaliTree {
    /**
     * Relative paths (sorted) of the classes passing [filter]. The result is a SUPERSET of the
     * classes whose text satisfies the filter's needles (it may contain false positives, never
     * misses one); the path regexes are exact.
     */
    fun candidates(filter: ClassFilter = ClassFilter()): List<String>

    /** Smali text of the class at [path] (as returned by [candidates]); modifications included. */
    fun read(path: String): String

    /** Replaces the smali text of an existing class. */
    fun write(path: String, text: String)

    /**
     * Path of the class [name] (`com/x/Y`, no `L` / `;`) searching every `smali*` dir in sorted
     * order, or null.
     */
    fun classPath(name: String): String?

    /**
     * Deletes every class under the directory [rel] (`a/b`) from every `smali*` dir; returns one
     * log line per dir it existed in (empty if it exists nowhere).
     */
    fun removeDir(rel: String): List<String>
}

/** In-memory [SmaliTree] over synthetic smali (unit tests, dry runs). */
class MemorySmaliTree(files: Map<String, String> = emptyMap()) : SmaliTree {
    private val files = java.util.TreeMap<String, String>(files)

    fun put(path: String, text: String) { files[path] = text }
    fun get(path: String): String? = files[path]
    fun paths(): Set<String> = files.keys

    override fun candidates(filter: ClassFilter): List<String> {
        val inc = filter.pathRegex?.let { PyRegex.compile(it) }
        val exc = filter.excludePathRegexes.map { PyRegex.compile(it) }
        return files.keys.filter { p ->
            (inc == null || inc.matcher(p).find()) && exc.none { it.matcher(p).find() }
        }
    }

    override fun read(path: String): String = files[path] ?: throw PatchFailure("class file $path not found")

    override fun write(path: String, text: String) {
        if (path !in files) throw PatchFailure("class file $path not found")
        files[path] = text
    }

    private fun dirs() = files.keys.map { it.substringBefore('/') }.filter { isSmaliDir(it) }.toSortedSet()

    override fun classPath(name: String): String? =
        dirs().map { "$it/$name.smali" }.firstOrNull { it in files }

    override fun removeDir(rel: String): List<String> {
        val done = ArrayList<String>()
        for (d in dirs()) {
            val prefix = "$d/$rel/"
            val hit = files.keys.filter { it.startsWith(prefix) }
            if (hit.isNotEmpty()) {
                hit.forEach { files.remove(it) }
                done.add("$d/$rel removed (${hit.size} files)")
            }
        }
        return done
    }
}

internal fun isSmaliDir(d: String) = d == "smali" || d.startsWith("smali_classes")

/**
 * A view of [inner] where every label (`:cond_8`, `:goto_0`, `:ss_skip_a`) reads as `:L`.
 *
 * Used for the applied-mode self-check: assembling the patched smali and disassembling it again
 * renumbers the labels, so the patched text can only be compared label-insensitively.
 */
class CanonicalLabelsTree(private val inner: SmaliTree) : SmaliTree {
    override fun candidates(filter: ClassFilter) = inner.candidates(filter)
    override fun read(path: String) = SmaliText.canonLabels(inner.read(path))
    override fun write(path: String, text: String) = throw PatchFailure("the self-check view is read-only")
    override fun classPath(name: String) = inner.classPath(name)
    override fun removeDir(rel: String): List<String> = throw PatchFailure("the self-check view is read-only")
}
