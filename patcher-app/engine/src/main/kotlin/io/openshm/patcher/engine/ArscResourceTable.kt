package io.openshm.patcher.engine

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import java.io.ByteArrayInputStream

/** The compiled `resources.arsc` of an APK as the [ResourceTable] the string ops need (ARSCLib). */
class ArscResourceTable(bytes: ByteArray) : ResourceTable {
    val table: TableBlock = TableBlock.load(ByteArrayInputStream(bytes))

    /** True once a string was added; an untouched table is not rewritten. */
    var modified = false
        private set

    private val pkg: PackageBlock
        get() = table.pickOne() ?: throw PatchFailure("resources.arsc has no package")

    override fun hasString(name: String): Boolean = table.getLocalResource("string", name) != null

    override fun hasStringsFile(qualifier: String): Boolean {
        val pair = pkg.getSpecTypePair("string") ?: return false
        val it = pair.typeBlockArray.iterator()
        while (it.hasNext()) if (it.next().resConfig.qualifiers == qualifier) return true
        return false
    }

    override fun addString(qualifier: String, name: String, text: String) {
        modified = true
        pkg.getOrCreate(qualifier, "string", name).setValueAsString(text)
    }

    override fun string(qualifier: String, name: String): String? =
        pkg.getEntry(qualifier, "string", name)?.valueAsString

    /** Package names of the table (aapt2 sets them to the manifest package). */
    fun packageNames(): List<String> = table.map { it.name ?: "" }

    /** Global string pool values (not resource names). */
    fun poolStrings(): List<String> = table.stringPool.map { it.get() ?: "" }

    /**
     * Renames every package and rewrites every global string pool value with [transform]; returns
     * how many names/values changed.
     */
    fun mapStrings(transform: (String) -> String): Int {
        var n = 0
        for (p in table) {
            val old = p.name ?: continue
            val new = transform(old)
            if (new != old) { p.name = new; n++ }
        }
        for (item in table.stringPool) {
            val old = item.get() ?: continue
            val new = transform(old)
            if (new != old) { item.set(new); n++ }
        }
        if (n > 0) modified = true
        return n
    }

    fun toBytes(): ByteArray {
        table.refresh()
        return table.bytes
    }
}
