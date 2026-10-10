package io.openshm.patcher.engine

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** An entry of a zip file as listed in its central directory. */
class ZipEntryInfo(
    val name: String,
    internal val nameBytes: ByteArray,
    val flags: Int,
    val method: Int,
    val dosTime: Int,
    val dosDate: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    internal val localOffset: Long,
    internal val versionMadeBy: Int,
    internal val externalAttrs: Long,
) {
    val isStored: Boolean get() = method == ZipFormat.STORED
    val isDirectory: Boolean get() = name.endsWith("/")
}

internal object ZipFormat {
    const val STORED = 0
    const val DEFLATED = 8
    const val LOCAL_SIG = 0x04034b50
    const val CENTRAL_SIG = 0x02014b50
    const val EOCD_SIG = 0x06054b50
    const val UTF8_FLAG = 0x0800
}

/**
 * Random-access reader of a (non zip64) zip/APK. Entries can be read decompressed or copied raw
 * (still compressed) to a [ZipWriter], so repacking an APK never recompresses what it keeps.
 */
class ZipReader(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val entries: List<ZipEntryInfo>
    private val byName = HashMap<String, ZipEntryInfo>()

    init {
        try {
            entries = readCentralDirectory()
        } catch (e: Throwable) {
            raf.close()
            throw e
        }
        for (e in entries) if (byName.put(e.name, e) != null) {
            raf.close()
            throw IOException("${file.name}: duplicate zip entry ${e.name}")
        }
    }

    fun entry(name: String): ZipEntryInfo? = byName[name]

    private fun readCentralDirectory(): List<ZipEntryInfo> {
        val len = raf.length()
        val tailLen = minOf(len, 22L + 65535L).toInt()
        val tail = ByteArray(tailLen)
        raf.seek(len - tailLen)
        raf.readFully(tail)
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var p = tailLen - 22
        while (p >= 0 && bb.getInt(p) != ZipFormat.EOCD_SIG) p--
        if (p < 0) throw IOException("not a zip file (no end of central directory)")
        val total = bb.getShort(p + 10).toInt() and 0xFFFF
        val cdSize = bb.getInt(p + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = bb.getInt(p + 16).toLong() and 0xFFFFFFFFL
        if (total == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) throw IOException("zip64 is not supported")
        if (cdOffset + cdSize > len) throw IOException("corrupt zip central directory")
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset)
        raf.readFully(cd)
        val c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<ZipEntryInfo>(total)
        var q = 0
        repeat(total) {
            if (q + 46 > cd.size || c.getInt(q) != ZipFormat.CENTRAL_SIG) throw IOException("corrupt zip central directory")
            val flags = c.getShort(q + 8).toInt() and 0xFFFF
            val nameLen = c.getShort(q + 28).toInt() and 0xFFFF
            val extraLen = c.getShort(q + 30).toInt() and 0xFFFF
            val commentLen = c.getShort(q + 32).toInt() and 0xFFFF
            if (q + 46 + nameLen + extraLen + commentLen > cd.size) throw IOException("corrupt zip central directory")
            val nameBytes = cd.copyOfRange(q + 46, q + 46 + nameLen)
            out.add(
                ZipEntryInfo(
                    name = String(nameBytes, Charsets.UTF_8),
                    nameBytes = nameBytes,
                    flags = flags,
                    method = c.getShort(q + 10).toInt() and 0xFFFF,
                    dosTime = c.getShort(q + 12).toInt() and 0xFFFF,
                    dosDate = c.getShort(q + 14).toInt() and 0xFFFF,
                    crc = c.getInt(q + 16).toLong() and 0xFFFFFFFFL,
                    compressedSize = c.getInt(q + 20).toLong() and 0xFFFFFFFFL,
                    size = c.getInt(q + 24).toLong() and 0xFFFFFFFFL,
                    localOffset = c.getInt(q + 42).toLong() and 0xFFFFFFFFL,
                    versionMadeBy = c.getShort(q + 4).toInt() and 0xFFFF,
                    externalAttrs = c.getInt(q + 38).toLong() and 0xFFFFFFFFL,
                ),
            )
            q += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun dataOffset(e: ZipEntryInfo): Long {
        val h = ByteArray(30)
        raf.seek(e.localOffset)
        raf.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != ZipFormat.LOCAL_SIG) throw IOException("corrupt zip: bad local header for ${e.name}")
        val nameLen = b.getShort(26).toInt() and 0xFFFF
        val extraLen = b.getShort(28).toInt() and 0xFFFF
        return e.localOffset + 30 + nameLen + extraLen
    }

    /** The decompressed content; fails if it is larger than [limit] bytes. */
    fun read(e: ZipEntryInfo, limit: Long = 1L shl 30): ByteArray {
        if (e.size > limit) throw IOException("${e.name} is too large (${e.size} bytes)")
        val out = ByteArray(e.size.toInt())
        open(e).use { s ->
            var n = 0
            while (n < out.size) {
                val r = s.read(out, n, out.size - n)
                if (r < 0) throw IOException("${e.name}: unexpected end of data")
                n += r
            }
            if (s.read() >= 0) throw IOException("${e.name}: longer than declared")
        }
        val crc = CRC32().apply { update(out) }.value
        if (crc != e.crc) throw IOException("${e.name}: CRC mismatch")
        return out
    }

    fun read(name: String): ByteArray = read(entry(name) ?: throw IOException("no entry $name"))

    /** Stream of the decompressed content. */
    fun open(e: ZipEntryInfo): InputStream {
        val start = dataOffset(e)
        val raw = RawStream(start, e.compressedSize)
        return when (e.method) {
            ZipFormat.STORED -> raw
            ZipFormat.DEFLATED -> object : java.util.zip.InflaterInputStream(raw, Inflater(true), 8192) {
                override fun close() {
                    inf.end()
                    super.close()
                }
            }
            else -> throw IOException("${e.name}: unsupported compression method ${e.method}")
        }
    }

    /** Copies the stored bytes of [e] (compressed as they are) to [out]. */
    fun copyRaw(e: ZipEntryInfo, out: OutputStream) {
        val s = RawStream(dataOffset(e), e.compressedSize)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
    }

    private inner class RawStream(private var pos: Long, private var left: Long) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            raf.seek(pos)
            val r = raf.read(b, off, n)
            if (r < 0) throw IOException("unexpected end of zip data")
            pos += r
            left -= r
            return r
        }
    }

    override fun close() = raf.close()
}

/**
 * Writes a zip/APK. Stored entries are aligned (zipalign style: padding in the local extra field)
 * because the platform maps `resources.arsc` and native libraries straight from the file.
 */
class ZipWriter(private val out: OutputStream) : Closeable {
    private class Done(val e: ZipEntryInfo, val offset: Long, val method: Int, val flags: Int, val crc: Long, val csize: Long, val size: Long)

    private var offset = 0L
    private val done = ArrayList<Done>()
    private val names = HashSet<String>()
    private var finished = false

    private fun put(b: ByteArray) {
        out.write(b)
        offset += b.size
    }

    private fun header(nameBytes: ByteArray, flags: Int, method: Int, time: Int, date: Int, crc: Long, csize: Long, size: Long, align: Int): ByteArray {
        var pad = 0
        if (method == ZipFormat.STORED && align > 1) {
            val dataStart = offset + 30 + nameBytes.size
            pad = ((align - dataStart % align) % align).toInt()
            if (pad in 1..3) pad += align
        }
        val bb = ByteBuffer.allocate(30 + nameBytes.size + pad).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(ZipFormat.LOCAL_SIG)
        bb.putShort(20)
        bb.putShort((flags and (ZipFormat.UTF8_FLAG)).toShort())
        bb.putShort(method.toShort())
        bb.putShort(time.toShort())
        bb.putShort(date.toShort())
        bb.putInt(crc.toInt())
        bb.putInt(csize.toInt())
        bb.putInt(size.toInt())
        bb.putShort(nameBytes.size.toShort())
        bb.putShort(pad.toShort())
        bb.put(nameBytes)
        if (pad > 0) {
            bb.putShort(0xD935.toShort())
            bb.putShort((pad - 4).toShort())
            if (pad - 4 >= 2) bb.putShort(align.toShort())
        }
        return bb.array() // remaining bytes are zero
    }

    private fun checkNew(name: String) {
        check(!finished) { "zip already finished" }
        if (!names.add(name)) throw IOException("duplicate zip entry $name")
    }

    /** Copies [e] from [src] as it is (same compression). */
    fun addRaw(src: ZipReader, e: ZipEntryInfo, align: Int = 1) {
        checkNew(e.name)
        val start = offset
        put(header(e.nameBytes, e.flags, e.method, e.dosTime, e.dosDate, e.crc, e.compressedSize, e.size, align))
        val counting = object : OutputStream() {
            override fun write(b: Int) { out.write(b); offset++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); offset += len }
        }
        src.copyRaw(e, counting)
        done.add(Done(e, start, e.method, e.flags and ZipFormat.UTF8_FLAG, e.crc, e.compressedSize, e.size))
    }

    /** Adds [data] under [name], stored or deflated; [like] gives the time stamp and attributes. */
    fun addBytes(name: String, data: ByteArray, stored: Boolean, align: Int = 1, like: ZipEntryInfo? = null) {
        checkNew(name)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val payload: ByteArray
        val method: Int
        if (stored) {
            payload = data
            method = ZipFormat.STORED
        } else {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            val buf = java.io.ByteArrayOutputStream(data.size / 2 + 64)
            try {
                d.setInput(data)
                d.finish()
                val chunk = ByteArray(32 * 1024)
                while (!d.finished()) buf.write(chunk, 0, d.deflate(chunk))
            } finally {
                d.end()
            }
            payload = buf.toByteArray()
            method = ZipFormat.DEFLATED
        }
        val time = like?.dosTime ?: DEFAULT_TIME
        val date = like?.dosDate ?: DEFAULT_DATE
        val info = ZipEntryInfo(name, nameBytes, ZipFormat.UTF8_FLAG, method, time, date, crc, payload.size.toLong(), data.size.toLong(), 0, like?.versionMadeBy ?: 20, like?.externalAttrs ?: 0)
        val start = offset
        put(header(nameBytes, ZipFormat.UTF8_FLAG, method, time, date, crc, payload.size.toLong(), data.size.toLong(), align))
        put(payload)
        done.add(Done(info, start, method, ZipFormat.UTF8_FLAG, crc, payload.size.toLong(), data.size.toLong()))
    }

    /** Writes the central directory. Does not close the stream. */
    fun finish() {
        check(!finished)
        finished = true
        val cdStart = offset
        for (d in done) {
            val e = d.e
            val bb = ByteBuffer.allocate(46 + e.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(ZipFormat.CENTRAL_SIG)
            bb.putShort(e.versionMadeBy.toShort())
            bb.putShort(20)
            bb.putShort(d.flags.toShort())
            bb.putShort(d.method.toShort())
            bb.putShort(e.dosTime.toShort())
            bb.putShort(e.dosDate.toShort())
            bb.putInt(d.crc.toInt())
            bb.putInt(d.csize.toInt())
            bb.putInt(d.size.toInt())
            bb.putShort(e.nameBytes.size.toShort())
            bb.putShort(0) // extra
            bb.putShort(0) // comment
            bb.putShort(0) // disk
            bb.putShort(0) // internal attrs
            bb.putInt(e.externalAttrs.toInt())
            bb.putInt(d.offset.toInt())
            bb.put(e.nameBytes)
            put(bb.array())
        }
        val cdSize = offset - cdStart
        if (done.size > 0xFFFF || offset > 0xFFFFFFFFL) throw IOException("the APK is too large for the zip format used (no zip64)")
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(ZipFormat.EOCD_SIG)
        eocd.putShort(0)
        eocd.putShort(0)
        eocd.putShort(done.size.toShort())
        eocd.putShort(done.size.toShort())
        eocd.putInt(cdSize.toInt())
        eocd.putInt(cdStart.toInt())
        eocd.putShort(0)
        put(eocd.array())
        out.flush()
    }

    override fun close() = out.close()

    private companion object {
        // 1980-01-01 00:00:00, a fixed stamp for entries that did not exist before
        const val DEFAULT_TIME = 0
        const val DEFAULT_DATE = (1 shl 5) or 1
    }
}
