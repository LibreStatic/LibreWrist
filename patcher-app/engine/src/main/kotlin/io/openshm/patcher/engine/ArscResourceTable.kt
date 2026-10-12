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

    private fun appPackage(id: Int): PackageBlock =
        table.firstOrNull { it.id == (id ushr 24) } ?: throw PatchFailure("resources.arsc has no package 0x${"%02x".format(id ushr 24)}")

    private fun definedEntries(pair: com.reandroid.arsc.container.SpecTypePair, index: Int): List<com.reandroid.arsc.value.Entry> {
        val out = ArrayList<com.reandroid.arsc.value.Entry>()
        val blocks = pair.typeBlocks
        while (blocks.hasNext()) {
            val e = blocks.next().getEntry(index)
            if (e != null && !e.isNull) out.add(e)
        }
        return out
    }

    override fun entryAt(id: Int): String? {
        val pair = appPackage(id).getSpecTypePair((id ushr 16) and 0xff) ?: return null
        val e = definedEntries(pair, id and 0xffff).firstOrNull() ?: return null
        return "${pair.typeName}/${e.name}"
    }

    override fun hasEntry(type: String, name: String): Boolean = table.getLocalResource(type, name) != null

    override fun defineNullEntry(type: String, name: String, id: Int) {
        val pkg = appPackage(id)
        val pair = pkg.getSpecTypePair((id ushr 16) and 0xff)
        if (pair == null || pair.typeName != type) {
            throw PatchFailure("resource type 0x${"%02x".format((id ushr 16) and 0xff)} is ${pair?.typeName ?: "missing"}, expected $type")
        }
        val index = id and 0xffff
        if (definedEntries(pair, index).isNotEmpty()) throw PatchFailure("resource slot 0x${"%08x".format(id)} is already defined")
        val entry = pair.getOrCreateEntry(index.toShort(), "")
        entry.setName(name, true)
        entry.setValueAsReference(0)
        modified = true
    }

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
