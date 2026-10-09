package io.openshm.patcher.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test
    fun `pm list packages plain and with -f`() {
        val out = """
            package:com.samsung.android.shealthmonitor
            package:/data/app/~~abc==/io.x.app-xyz==/base.apk=io.x.app
            package:io.y.app installer=com.android.vending
            noise line
            package:not a package
        """.trimIndent()
        assertEquals(
            listOf("com.samsung.android.shealthmonitor", "io.x.app", "io.y.app"),
            PmParsers.parsePackageList(out),
        )
    }

    @Test
    fun `pm path keeps base and splits in order`() {
        val out = "package:/data/app/~~a==/p-b==/base.apk\npackage:/data/app/~~a==/p-b==/split_config.arm64_v8a.apk\n"
        assertEquals(
            listOf("/data/app/~~a==/p-b==/base.apk", "/data/app/~~a==/p-b==/split_config.arm64_v8a.apk"),
            PmParsers.parsePaths(out),
        )
        assertTrue(PmParsers.parsePaths("").isEmpty())
    }

    @Test
    fun `dumpsys version uses the first occurrence`() {
        val out = """
            Packages:
              Package [io.x.app] (abc):
                versionCode=1403200 minSdk=30 targetSdk=34
                versionName=14.1.0.320
            Hidden system packages:
                versionCode=1 minSdk=30 targetSdk=30
                versionName=0.0
        """.trimIndent()
        assertEquals(PackageVersion(1403200L, "14.1.0.320"), PmParsers.parseVersion(out))
    }

    @Test
    fun `dumpsys without a package yields null and null versionName is dropped`() {
        assertNull(PmParsers.parseVersion("Unable to find package: io.x.app"))
        assertNull(PmParsers.parseVersion("versionCode=5 minSdk=1\n versionName=null")!!.versionName)
    }

    @Test
    fun `install-create output`() {
        assertEquals(1234, InstallOutput.parseSessionId("Success: created install session [1234]\n"))
        assertNull(InstallOutput.parseSessionId("Failure [INSTALL_FAILED_INTERNAL_ERROR]"))
    }

    @Test
    fun `install-write and commit success detection`() {
        assertTrue(InstallOutput.isSuccess("Success: streamed 11 bytes\n"))
        assertTrue(InstallOutput.isSuccess("Success\n"))
        assertFalse(InstallOutput.isSuccess("Failure [INSTALL_FAILED_ABORTED: nope]"))
        assertFalse(InstallOutput.isSuccess("Success: streamed 1 bytes\nFailure [X]"))
        assertFalse(InstallOutput.isSuccess(""))
    }

    @Test
    fun `install failure carries code and message`() {
        val f = InstallOutput.failure(
            ShellResult(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]\n", ""),
            InstallResult.Stage.COMMIT,
        )
        assertEquals("INSTALL_FAILED_VERSION_DOWNGRADE", f.code)
        assertEquals("Downgrade detected", f.message)
        val g = InstallOutput.failure(ShellResult(1, "Failure [INSTALL_FAILED_ABORTED]", ""), InstallResult.Stage.WRITE)
        assertEquals("INSTALL_FAILED_ABORTED", g.code)
        val h = InstallOutput.failure(ShellResult(255, "", "cmd: not found"), InstallResult.Stage.CREATE)
        assertNull(h.code)
        assertEquals("cmd: not found", h.message)
    }
}
