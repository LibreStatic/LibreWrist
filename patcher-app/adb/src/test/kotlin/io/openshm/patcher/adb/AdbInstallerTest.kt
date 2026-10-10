package io.openshm.patcher.adb

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdbInstallerTest {
    private fun apk(name: String, content: ByteArray) = ApkSource(name, content.size.toLong()) { ByteArrayInputStream(content) }

    /** Fake device: records shell commands and the bytes sent to each install-write. */
    private class Device(
        val createOut: String = "Success: created install session [77]\n",
        val writeOut: (String) -> String = { "Success: streamed bytes\n" },
        val commitOut: String = "Success\n",
    ) {
        val shells = mutableListOf<String>()
        val writes = mutableMapOf<String, FakeChannel>()

        fun transport() = scriptedTransport(
            shell = { cmd ->
                shells += cmd
                when {
                    cmd.startsWith("cmd package install-create") -> ok(createOut)
                    cmd.startsWith("cmd package install-commit") -> ok(commitOut)
                    else -> ok()
                }
            },
            exec = { dest ->
                val ch = FakeChannel(writeOut(dest).toByteArray())
                writes[dest] = ch
                ch
            },
        )
    }

    @Test
    fun `installs several APKs in one session and reports progress`() = runBlocking {
        val device = Device()
        val base = ByteArray(100_000) { (it % 251).toByte() }
        val split = ByteArray(5)
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = AdbInstaller(AdbClient(device.transport())).install(
            listOf(apk("base.apk", base), apk("split_a.apk", split)),
            InstallOptions(userId = 0),
        ) { w, t -> synchronized(progress) { progress += w to t } }

        assertEquals(InstallResult.Success(77), result)
        assertEquals("cmd package install-create -S 100005 -r --user 0", device.shells[0])
        assertEquals("cmd package install-commit 77", device.shells[1])
        assertEquals(2, device.shells.size)
        assertArrayEquals(base, device.writes["exec:cmd package install-write -S 100000 77 base.apk -"]!!.written.toByteArray())
        assertArrayEquals(split, device.writes["exec:cmd package install-write -S 5 77 split_a.apk -"]!!.written.toByteArray())
        assertEquals(100_005L to 100_005L, progress.last())
    }

    @Test
    fun `option flags are mapped`() {
        assertEquals(
            "cmd package install-create -S 9 -d -g",
            InstallCommands.create(9, InstallOptions(replaceExisting = false, allowDowngrade = true, grantRuntimePermissions = true)),
        )
    }

    @Test
    fun `create failure returns a typed failure and abandons nothing`() = runBlocking {
        val device = Device(createOut = "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n")
        val r = AdbInstaller(AdbClient(device.transport())).install(listOf(apk("base.apk", ByteArray(3))))
        assertEquals(
            InstallResult.Failure("INSTALL_FAILED_INSUFFICIENT_STORAGE", "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", InstallResult.Stage.CREATE),
            r,
        )
        assertTrue(device.shells.none { it.contains("abandon") })
    }

    @Test
    fun `commit failure abandons the session`() = runBlocking {
        val device = Device(commitOut = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]\n")
        val r = AdbInstaller(AdbClient(device.transport())).install(listOf(apk("base.apk", ByteArray(3))))
        assertTrue(r is InstallResult.Failure)
        r as InstallResult.Failure
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", r.code)
        assertEquals(InstallResult.Stage.COMMIT, r.stage)
        assertEquals("cmd package install-abandon 77", device.shells.last())
    }

    @Test
    fun `write failure stops, skips commit and abandons`() = runBlocking {
        val device = Device(writeOut = { "Failure [INSTALL_FAILED_INVALID_APK]\n" })
        val r = AdbInstaller(AdbClient(device.transport())).install(listOf(apk("base.apk", ByteArray(3)), apk("s.apk", ByteArray(3))))
        assertEquals(InstallResult.Stage.WRITE, (r as InstallResult.Failure).stage)
        assertEquals(1, device.writes.size)
        assertTrue(device.shells.none { it.contains("install-commit") })
        assertEquals("cmd package install-abandon 77", device.shells.last())
    }

    @Test
    fun `an APK shorter than declared abandons the session and throws`() = runBlocking {
        val device = Device()
        val lying = ApkSource("base.apk", 10) { ByteArrayInputStream(ByteArray(4)) }
        try {
            AdbInstaller(AdbClient(device.transport())).install(listOf(lying))
            fail()
        } catch (e: AdbException.Protocol) {
            assertEquals("cmd package install-abandon 77", device.shells.last())
        }
    }

    @Test
    fun `rejects unsafe entry names and duplicates`() {
        for (bad in listOf("a b.apk", "../x.apk", "x.apk; id", "noext", "")) {
            try {
                ApkSource(bad, 1) { ByteArrayInputStream(ByteArray(1)) }
                fail("accepted $bad")
            } catch (e: IllegalArgumentException) {
            }
        }
        val device = Device()
        try {
            runBlocking {
                AdbInstaller(AdbClient(device.transport())).install(listOf(apk("a.apk", ByteArray(1)), apk("a.apk", ByteArray(1))))
            }
            fail()
        } catch (e: IllegalArgumentException) {
        }
    }
}
