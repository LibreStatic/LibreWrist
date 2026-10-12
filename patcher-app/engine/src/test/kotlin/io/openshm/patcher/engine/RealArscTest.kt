package io.openshm.patcher.engine

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test

/** Applies `defineResourceEntry` to the real `resources.arsc` of the official watch APK (git-ignored `apks/`; skipped if absent). */
class RealArscTest {
    @Test fun anchorOnTheRealWatchTable() {
        val apk = File(System.getProperty("shm.repoRoot"), "apks/shm-watch-1.5.1.022.apk")
        Assume.assumeTrue(apk.isFile)
        val bytes = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("resources.arsc")).readBytes() }
        val before = ArscResourceTable(bytes)
        assertEquals(null, before.entryAt(0x7f010000))
        assertEquals("array/android_wear_capabilities", before.entryAt(0x7f030000))
        val args = """{"type": "anim", "name": "openshm_gms_resources_anchor", "id": "0x7f010000"}"""
        val ctx = PatchContext(MemorySmaliTree(), resourcesProvider = { before })
        op("defineResourceEntry", args).apply(ctx)
        val back = ArscResourceTable(before.toBytes())
        assertEquals("anim/openshm_gms_resources_anchor", back.entryAt(0x7f010000))
        assertEquals("array/android_wear_capabilities", back.entryAt(0x7f030000))
        assertEquals(1, op("defineResourceEntry", args).verifyApplied(PatchContext(MemorySmaliTree(), resourcesProvider = { back })).size)
    }
}
