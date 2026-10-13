package io.openshm.patcher.engine

import java.io.EOFException
import java.io.InputStream

/**
 * Package-chunk names of a compiled `resources.arsc`, read and rewritten without loading the table
 * (Samsung Health's is ~175 MB). Chunks are walked by their 8-byte headers and skipped.
 */
internal object ArscPackages {
    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val NAME_OFFSET = 12
    private const val NAME_CHARS = 128
    private const val NAME_BYTES = NAME_CHARS * 2

    /** The 256-byte, zero padded UTF-16LE form of [name]; at most 127 characters. */
    fun encodeName(name: String): ByteArray {
        if (name.length > NAME_CHARS - 1) throw PatchFailure("package name '$name' does not fit the resources.arsc package chunk (max ${NAME_CHARS - 1} characters)")
        return ByteArray(NAME_BYTES).also { name.toByteArray(Charsets.UTF_16LE).copyInto(it) }
    }

    /** (offset of the name field, name) of every package chunk, reading [input] once, never whole. */
    fun scan(input: InputStream): List<Pair<Long, String>> = input.use { s ->
        val out = ArrayList<Pair<Long, String>>()
        val head = ByteArray(12)
        readFully(s, head, 12)
        if (u16(head, 0) != TYPE_TABLE) throw PatchFailure("resources.arsc is not a resource table")
        val tableSize = u32(head, 4)
        var pos = u16(head, 2).toLong()
        val hdr = ByteArray(8)
        while (pos + 8 <= tableSize) {
            readFully(s, hdr, 8)
            val type = u16(hdr, 0)
            val headerSize = u16(hdr, 2)
            val size = u32(hdr, 4)
            if (size < 8 || size < headerSize || pos + size > tableSize) throw PatchFailure("resources.arsc has a corrupt chunk at $pos")
            var consumed = 8L
            if (type == TYPE_PACKAGE) {
                if (headerSize < NAME_OFFSET + NAME_BYTES) throw PatchFailure("resources.arsc has a short package chunk at $pos")
                val rest = ByteArray(NAME_OFFSET + NAME_BYTES - 8)
                readFully(s, rest, rest.size)
                var n = 0
                while (n < NAME_CHARS && (rest[NAME_OFFSET - 8 + 2 * n].toInt() or rest[NAME_OFFSET - 8 + 2 * n + 1].toInt()) != 0) n++
                out.add((pos + NAME_OFFSET) to String(rest, NAME_OFFSET - 8, 2 * n, Charsets.UTF_16LE))
                consumed += rest.size
            }
            skipFully(s, size - consumed)
            pos += size
        }
        out
    }

    fun packageNames(input: InputStream): List<String> = scan(input).map { it.second }

    /** The writes (entry offset, bytes) that rename the package chunks named [old] to [new]. */
    fun renamePatches(input: InputStream, old: String, new: String): List<Pair<Long, ByteArray>> {
        val encoded = encodeName(new)
        return scan(input).filter { it.second == old }.map { it.first to encoded }
    }

    /** Renames in place in a table held in memory; returns how many package chunks changed. */
    fun rename(bytes: ByteArray, old: String, new: String): Int {
        val patches = renamePatches(bytes.inputStream(), old, new)
        for ((off, b) in patches) b.copyInto(bytes, off.toInt())
        return patches.size
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    private fun readFully(s: InputStream, b: ByteArray, n: Int) {
        var r = 0
        while (r < n) {
            val k = s.read(b, r, n - r)
            if (k < 0) throw EOFException("resources.arsc ends inside a chunk header")
            r += k
        }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) { left -= k; continue }
            if (s.read() < 0) throw EOFException("resources.arsc ends inside a chunk")
            left--
        }
    }
}
