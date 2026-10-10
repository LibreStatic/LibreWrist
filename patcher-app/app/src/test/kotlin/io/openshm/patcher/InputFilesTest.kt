package io.openshm.patcher

import io.openshm.patcher.data.ArchiveInfo
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.ImportResult
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.InputFiles
import io.openshm.patcher.data.RejectReason
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InputFilesTest {
    private lateinit var dir: File
    private val content = ByteArray(700_000) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private fun input(sha: String = hash, version: String = "1.0") = InputApp(
        "demo", "Demo", version, Device.Phone, sha, "com.example.demo", "x/demo", emptyList(),
    )

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "inputs-test-${System.nanoTime()}")
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun matchingHashIsRenamedAndRecorded() {
        val files = InputFiles(dir)
        val progress = mutableListOf<Long>()
        val result = files.import(input(), content.inputStream(), content.size.toLong(), { null }) { d, _ -> progress += d }
        assertEquals(ImportResult.Verified(content.size.toLong()), result)
        assertTrue(files.file("demo").readBytes().contentEquals(content))
        assertFalse(File(dir, "demo.apk.part").exists())
        assertEquals(content.size.toLong(), progress.last())
        assertEquals(content.size.toLong(), files.status(input())?.sizeBytes)
    }

    @Test
    fun mismatchDeletesTheTempFileAndNamesTheReason() {
        val files = InputFiles(dir)
        fun reject(info: ArchiveInfo?) =
            (files.import(input(sha = "0".repeat(64)), content.inputStream(), -1, { info }) as ImportResult.Rejected).reason
        assertEquals(RejectReason.WrongApp, reject(ArchiveInfo("com.other", "1.0")))
        assertEquals(RejectReason.WrongVersion, reject(ArchiveInfo("com.example.demo", "2.0")))
        assertEquals(RejectReason.NotExpectedFile, reject(ArchiveInfo("com.example.demo", "1.0")))
        assertEquals(RejectReason.NotExpectedFile, reject(null))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        assertNull(files.status(input()))
    }

    @Test
    fun readFailureIsUnreadableAndLeavesNothing() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("gone")
        }
        val result = InputFiles(dir).import(input(), broken, -1, { null })
        assertEquals(ImportResult.Rejected(RejectReason.Unreadable), result)
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun aRecordFromAnOlderCatalogueIsStaleAndTheFileIsDeleted() {
        val files = InputFiles(dir)
        files.import(input(), content.inputStream(), -1, { null })
        assertNotNull(files.status(input()))
        assertNull(files.status(input(sha = "f".repeat(64))))
        assertFalse(files.file("demo").exists())
        assertNull(files.status(input()))
    }

    @Test
    fun aTruncatedFileIsNotVerified() {
        val files = InputFiles(dir)
        files.import(input(), content.inputStream(), -1, { null })
        files.file("demo").writeBytes(content.copyOf(10))
        assertNull(files.status(input()))
    }
}
