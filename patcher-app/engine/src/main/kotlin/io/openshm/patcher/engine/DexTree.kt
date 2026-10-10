package io.openshm.patcher.engine

import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import java.io.StringReader
import java.io.StringWriter
import java.util.TreeMap
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream

/** A rebuilt dex file: the APK entry name (`classes3.dex`) and its bytes. */
class DexOutput(val entryName: String, val bytes: ByteArray)

/** The smali of classes that the pipeline adds as a new dex (the extra code a profile injects). */
fun interface InjectionSource {
    /** Relative path (`com/x/Y.smali`) -> smali text, already carrying the real package prefix. */
    fun classes(): Map<String, String>
}

/**
 * [SmaliTree] over the `classes*.dex` files of an APK: classes are disassembled on demand
 * (baksmali, with the options apktool uses) and only the dex files whose classes were modified
 * or removed are rebuilt (smali assembler + dexlib2). Unmodified classes are copied as they are.
 *
 * A trigram index (built once, on the first filter with needles) bounds the work: a class is
 * only disassembled for an op if its index admits the op's needles. The index never excludes a
 * class that contains a needle, so results are the same as scanning every class.
 */
class DexTree(dexFiles: List<Pair<String, ByteArray>>, private val apiLevel: Int) : SmaliTree {
    private class Entry(val dex: Int, val type: String, val path: String, val def: DexBackedClassDef)

    private val names = dexFiles.map { it.first }
    private val dexes: List<DexBackedDexFile>
    private val entries = TreeMap<String, Entry>()
    private val edited = HashMap<String, String>()
    private val removed = HashSet<String>()
    private val options = BaksmaliOptions().apply {
        parameterRegisters = true
        localsDirective = true
        sequentialLabels = true
        debugInfo = true
        codeOffsets = false
        accessorComments = false
        implicitReferences = false
        deodex = false
        registerInfo = 0
        apiLevel = this@DexTree.apiLevel
    }

    // Bounded cache of disassembled, unmodified classes.
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        private var chars = 0L
        override fun put(key: String, value: String): String? {
            chars += value.length
            return super.put(key, value)
        }
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            if (chars > CACHE_CHARS) {
                chars -= eldest.value.length
                return true
            }
            return false
        }
    }

    private var index: Map<String, LongArray>? = null

    init {
        val list = ArrayList<DexBackedDexFile>()
        for ((i, f) in dexFiles.withIndex()) {
            val opcodes = Opcodes.forApi(apiLevel)
            val dex = DexBackedDexFile(opcodes, f.second)
            list.add(dex)
            val dir = smaliDirOf(f.first)
            for (def in dex.classes) {
                val type = def.type
                val rel = type.substring(1, type.length - 1) + ".smali"
                val path = "$dir/$rel"
                if (entries.containsKey(path)) throw PatchFailure("duplicate class $type in the dex files")
                entries[path] = Entry(i, type, path, def)
            }
        }
        dexes = list
    }

    val dexNames: List<String> get() = names

    override fun classPath(name: String): String? {
        val dirs = entries.keys.map { it.substringBefore('/') }.toSortedSet()
        return dirs.map { "$it/$name.smali" }.firstOrNull { it in entries && it !in removed }
    }

    override fun candidates(filter: ClassFilter): List<String> {
        val inc = filter.pathRegex?.let { PyRegex.compile(it) }
        val exc = filter.excludePathRegexes.map { PyRegex.compile(it) }
        val needles = filter.requireAll.filter { it.length >= 3 }
        val any = filter.requireAny
        val anyIndexable = any.isNotEmpty() && any.all { it.length >= 3 }
        val idx = if (needles.isNotEmpty() || anyIndexable) index() else null
        val out = ArrayList<String>()
        for (path in entries.keys) {
            if (path in removed) continue
            if (inc != null && !inc.matcher(path).find()) continue
            if (exc.any { it.matcher(path).find() }) continue
            if (idx != null) {
                val text = edited[path]
                if (text != null) {
                    if (filter.requireAll.any { it !in text }) continue
                    if (any.isNotEmpty() && any.none { it in text }) continue
                } else {
                    val bits = idx.getValue(path)
                    if (needles.any { !admits(bits, it) }) continue
                    if (anyIndexable && any.none { admits(bits, it) }) continue
                }
            }
            out.add(path)
        }
        return out
    }

    override fun read(path: String): String {
        edited[path]?.let { return it }
        val e = entries[path]
        if (e == null || path in removed) throw PatchFailure("class file $path not found")
        synchronized(cache) { cache[path]?.let { return it } }
        val text = disassemble(e.def)
        synchronized(cache) { cache[path] = text }
        return text
    }

    override fun write(path: String, text: String) {
        if (path !in entries || path in removed) throw PatchFailure("class file $path not found")
        edited[path] = text
        synchronized(cache) { cache.remove(path) }
    }

    override fun removeDir(rel: String): List<String> {
        val done = ArrayList<String>()
        val dirs = entries.keys.map { it.substringBefore('/') }.filter { isSmaliDir(it) }.toSortedSet()
        for (d in dirs) {
            val prefix = "$d/$rel/"
            val hit = entries.keys.filter { it.startsWith(prefix) && it !in removed }
            if (hit.isNotEmpty()) {
                removed.addAll(hit)
                hit.forEach { edited.remove(it) }
                done.add("$d/$rel removed (${hit.size} files)")
            }
        }
        return done
    }

    /** Paths of the classes modified or removed so far. */
    fun touchedPaths(): Set<String> = edited.keys + removed

    private fun disassemble(def: ClassDef): String {
        val sw = StringWriter(4096)
        // No class context: apktool's smali spells out the defining class in field/method references.
        val writer = BaksmaliWriter(sw)
        ClassDefinition(options, def).writeTo(writer)
        writer.flush()
        return sw.toString()
    }

    // ------------------------------------------------------------------ trigram index

    private fun index(): Map<String, LongArray> {
        index?.let { return it }
        val built = HashMap<String, LongArray>(entries.size * 2)
        for ((path, e) in entries) {
            if (path in removed) continue
            built[path] = bitsOf(edited[path] ?: disassemble(e.def))
        }
        index = built
        return built
    }

    private fun bitsOf(text: String): LongArray {
        val bits = bitCount(text.length)
        val arr = LongArray(bits ushr 6)
        val mask = bits - 1
        for (i in 0..text.length - 3) {
            val h = hash(text[i], text[i + 1], text[i + 2]) and mask
            arr[h ushr 6] = arr[h ushr 6] or (1L shl (h and 63))
        }
        return arr
    }

    private fun admits(bits: LongArray, needle: String): Boolean {
        val mask = (bits.size shl 6) - 1
        for (i in 0..needle.length - 3) {
            val h = hash(needle[i], needle[i + 1], needle[i + 2]) and mask
            if (bits[h ushr 6] and (1L shl (h and 63)) == 0L) return false
        }
        return true
    }

    private fun hash(a: Char, b: Char, c: Char): Int {
        var h = a.code * -1640531535 + b.code * -2048144777 + c.code * -1028477379
        h = h xor (h ushr 15)
        h *= -2048144777
        return h xor (h ushr 13)
    }

    private fun bitCount(len: Int): Int {
        var b = 256
        while (b < len * 2 && b < 65536) b = b shl 1
        return b
    }

    // ------------------------------------------------------------------ rebuild

    private fun assemble(builder: DexBuilder, path: String, text: String) = assembleClass(builder, path, text, apiLevel)

    private fun write(pool: DexPool): ByteArray {
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    /**
     * The dex files to put in the APK: the rebuilt versions of those with modified classes, the
     * untouched ones are reported as unchanged (not in the result). [injections] become a new
     * dex file after the last existing one; its classes must not exist yet.
     */
    fun rebuild(injections: List<InjectionSource> = emptyList()): RebuildResult {
        val changedDexes = (edited.keys + removed).map { entries.getValue(it).dex }.toSortedSet()
        val out = ArrayList<DexOutput>()
        for (d in changedDexes) {
            val pool = DexPool(Opcodes.forApi(apiLevel))
            val builder = DexBuilder(Opcodes.forApi(apiLevel))
            for (e in entries.values) {
                if (e.dex != d || e.path in removed) continue
                val text = edited[e.path]
                pool.internClass(if (text == null) e.def else assemble(builder, e.path, text))
            }
            out.add(DexOutput(names[d], write(pool)))
        }
        var injected: DexOutput? = null
        val sources = injections.map { it.classes() }
        if (sources.any { it.isNotEmpty() }) {
            val pool = DexPool(Opcodes.forApi(apiLevel))
            val builder = DexBuilder(Opcodes.forApi(apiLevel))
            val seen = HashSet<String>()
            for (files in sources) for ((rel, text) in files.toSortedMap()) {
                if (!seen.add(rel)) throw PatchFailure("inject: $rel is provided twice")
                val existing = entries.keys.firstOrNull { it.substringAfter('/') == rel && it !in removed }
                if (existing != null) throw PatchFailure("inject: $existing already present")
                pool.internClass(assemble(builder, rel, text))
            }
            injected = DexOutput("classes${dexes.size + 1}.dex", write(pool))
        }
        return RebuildResult(out, injected, injectedClassCount = sources.sumOf { it.size })
    }

    class RebuildResult(val changed: List<DexOutput>, val injected: DexOutput?, val injectedClassCount: Int)

    companion object {
        fun assembleClass(builder: DexBuilder, path: String, text: String, apiLevel: Int): ClassDef {
            val lexer = smaliFlexLexer(StringReader(text), apiLevel)
            lexer.setSourceFile(java.io.File(path))
            val tokens = CommonTokenStream(lexer)
            val parser = smaliParser(tokens)
            parser.setApiLevel(apiLevel)
            parser.setVerboseErrors(true)
            val result = parser.smali_file()
            if (parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) {
                throw PatchFailure("$path: the patched smali does not assemble")
            }
            val stream = CommonTreeNodeStream(result.tree)
            stream.tokenStream = tokens
            val walker = smaliTreeWalker(stream)
            walker.setApiLevel(apiLevel)
            walker.setVerboseErrors(true)
            walker.setDexBuilder(builder)
            return walker.smali_file()
        }

        /** A dex file holding the classes given as smali text (path -> text); used by tests and tools. */
        fun buildDex(files: Map<String, String>, apiLevel: Int): ByteArray {
            val pool = DexPool(Opcodes.forApi(apiLevel))
            val builder = DexBuilder(Opcodes.forApi(apiLevel))
            for ((p, t) in files.toSortedMap()) pool.internClass(assembleClass(builder, p, t, apiLevel))
            val store = MemoryDataStore()
            pool.writeTo(store)
            return store.data
        }

        private const val CACHE_CHARS = 48L * 1024 * 1024

        /** `classes.dex` -> `smali`, `classes3.dex` -> `smali_classes3`. */
        fun smaliDirOf(entryName: String): String {
            val m = Regex("""^classes(\d*)\.dex$""").matchEntire(entryName)
                ?: throw PatchFailure("not a dex entry name: $entryName")
            return if (m.groupValues[1].isEmpty()) "smali" else "smali_classes${m.groupValues[1]}"
        }
    }
}
