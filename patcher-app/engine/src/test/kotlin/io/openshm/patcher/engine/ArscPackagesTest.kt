package io.openshm.patcher.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArscPackagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private inline fun <reified E : Throwable> fails(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is E) return; throw e }
        throw AssertionError("expected ${E::class.simpleName}")
    }

    private val old = "com.sec.android.app.shealth"
    private val new = "net.example.shealth"

    private fun chunk(type: Int, headerSize: Int, body: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(headerSize + body.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(type.toShort()).putShort(headerSize.toShort()).putInt(headerSize + body.size)
        bb.position(headerSize)
        bb.put(body)
        return bb.array()
    }

    private fun packageChunk(id: Int, name: String): ByteArray {
        val c = chunk(0x0200, 288, ByteArray(40) { 7 })
        ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN).putInt(8, id)
        ArscPackages.encodeName(name).copyInto(c, 12)
        return c
    }

    /** A table: header, a "string pool" of [poolBytes], then packages. */
    private fun arsc(poolBytes: Int, vararg pkgs: Pair<Int, String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(chunk(0x0001, 28, ByteArray(poolBytes) { (it % 251).toByte() }))
        for ((id, n) in pkgs) body.write(packageChunk(id, n))
        val h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        h.putShort(2).putShort(12).putInt(12 + body.size()).putInt(pkgs.size)
        return h.array() + body.toByteArray()
    }

    private fun apk(arsc: ByteArray, stored: Boolean): ZipReader {
        val f = tmp.newFile()
        ZipWriter(FileOutputStream(f)).use { z ->
            z.addBytes("a.txt", "hello".toByteArray(), false)
            z.addBytes("resources.arsc", arsc, stored, 4)
            z.finish()
        }
        return ZipReader(f)
    }

    private fun rewrite(src: ZipReader, rename: Pair<String, String>?): File {
        val out = tmp.newFile()
        Patcher.writeApk(src, out, null, null, emptyMap(), null, arscRename = rename)
        return out
    }

    @Test fun scansPackageNames() {
        val a = arsc(1000, 0x7f to old, 0x01 to "android")
        assertEquals(listOf(old, "android"), ArscPackages.packageNames(a.inputStream()))
    }

    @Test fun streamedRenameOfStoredEntryKeepsCrcAndRest() {
        val a = arsc(300_000, 0x7f to old)
        val out = rewrite(apk(a, stored = true), old to new)
        ZipFile(out).use { z ->
            val e = z.getEntry("resources.arsc")
            assertEquals(java.util.zip.ZipEntry.STORED, e.method)
            val b = z.getInputStream(e).readBytes() // ZipFile verifies the CRC at end of stream
            assertEquals(a.size, b.size)
            assertEquals(listOf(new), ArscPackages.packageNames(b.inputStream()))
            val expected = a.copyOf().also { ArscPackages.rename(it, old, new) }
            assertArrayEquals(expected, b)
            assertEquals(e.crc, java.util.zip.CRC32().also { it.update(b) }.value)
            assertEquals("hello", String(z.getInputStream(z.getEntry("a.txt")).readBytes()))
        }
    }

    @Test fun deflatedEntryIsWrittenStoredAndRenamed() {
        val a = arsc(5000, 0x7f to old)
        val out = rewrite(apk(a, stored = false), old to new)
        ZipFile(out).use { z ->
            val e = z.getEntry("resources.arsc")
            assertEquals(java.util.zip.ZipEntry.STORED, e.method)
            assertEquals(listOf(new), ArscPackages.packageNames(z.getInputStream(e).readBytes().inputStream()))
        }
    }

    @Test fun otherPackagesAndUnmatchedEntriesAreUntouched() {
        val a = arsc(100, 0x7f to old, 0x01 to "android")
        val out = rewrite(apk(a, stored = true), old to new)
        ZipFile(out).use { z ->
            assertEquals(listOf(new, "android"), ArscPackages.packageNames(z.getInputStream(z.getEntry("resources.arsc")).readBytes().inputStream()))
        }
        val same = rewrite(apk(a, stored = true), "other.pkg" to new)
        ZipFile(same).use { z -> assertArrayEquals(a, z.getInputStream(z.getEntry("resources.arsc")).readBytes()) }
    }

    @Test fun inMemoryRename() {
        val a = arsc(100, 0x7f to old)
        assertEquals(1, ArscPackages.rename(a, old, new))
        assertEquals(listOf(new), ArscPackages.packageNames(a.inputStream()))
    }

    @Test fun nameTooLongFails() {
        fails<PatchFailure> { ArscPackages.encodeName("a".repeat(128)) }
        ArscPackages.encodeName("a".repeat(127))
        fails<PatchFailure> { rewrite(apk(arsc(10, 0x7f to old), true), old to "a".repeat(128)) }
    }

    @Test fun rebrandSchedulesRenameAndVerifyChecksTheTable() {
        val manifest = DomManifest("""<manifest xmlns:android="$ANDROID_NS" package="$old"/>""")
        val args = """{"oldPkg": "$old", "newPkg": "$new"}"""
        val renamed = apk(arsc(100, 0x7f to new), true)
        val stale = apk(arsc(100, 0x7f to old), true)

        val ctx = PatchContext(MemorySmaliTree(), { manifest }, filesProvider = { ZipApkFiles(stale) })
        val log = op("manifestRebrand", args).apply(ctx)
        assertTrue(log.toString(), log.contains("resources.arsc :: package $old -> $new"))
        assertEquals(old to new, ctx.arscPackageRename)

        fails<PatchFailure> {
            op("manifestRebrand", args).verifyApplied(PatchContext(MemorySmaliTree(), { manifest }, filesProvider = { ZipApkFiles(stale) }))
        }
        val ok = op("manifestRebrand", args).verifyApplied(PatchContext(MemorySmaliTree(), { manifest }, filesProvider = { ZipApkFiles(renamed) }))
        assertTrue(ok.contains("resources.arsc :: package $new"))
    }

    @Test fun apkWithoutArscOrSamePackageSkipsTheTable() {
        val manifest = DomManifest("""<manifest xmlns:android="$ANDROID_NS" package="$old"/>""")
        val f = tmp.newFile()
        ZipWriter(FileOutputStream(f)).use { z -> z.addBytes("a.txt", byteArrayOf(1), false); z.finish() }
        val ctx = PatchContext(MemorySmaliTree(), { manifest }, filesProvider = { ZipApkFiles(ZipReader(f)) })
        op("manifestRebrand", """{"oldPkg": "$old", "newPkg": "$new"}""").apply(ctx)
        assertEquals(null, ctx.arscPackageRename)
        assertEquals(emptyList<String>(), op("manifestRebrand", """{"oldPkg": "$old", "newPkg": "$old"}""").verifyApplied(ctx))
    }
}
