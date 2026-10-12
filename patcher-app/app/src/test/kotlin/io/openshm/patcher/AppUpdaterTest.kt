package io.openshm.patcher

import io.openshm.patcher.data.AppUpdateChecker
import io.openshm.patcher.data.AppUpdateResult
import io.openshm.patcher.data.AppUpdateSettings
import io.openshm.patcher.data.AppUpdater
import io.openshm.patcher.data.CatalogueFetcher
import io.openshm.patcher.data.RefusedResponseException
import io.openshm.patcher.data.fileHasSha256
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The release channel: tag and asset rules, the hourly limit and the hash check of the download. */
class AppUpdaterTest {
    private class FakeFetcher(private val responses: Map<String, ByteArray> = emptyMap(), private val error: IOException? = null) : CatalogueFetcher {
        val requests = mutableListOf<String>()
        override fun get(url: String, maxBytes: Int): ByteArray {
            requests += url
            error?.let { throw it }
            val body = responses[url] ?: throw IOException("HTTP 404")
            if (body.size > maxBytes) throw RefusedResponseException("response larger than $maxBytes bytes")
            return body
        }
    }

    private class MemSettings(override var autoCheck: Boolean = true, override var lastCheckMillis: Long = 0L, override var lastNotifiedBuild: Long = 0L) : AppUpdateSettings

    private val apiUrl = "https://api.github.com/repos/owner/name/releases/latest"
    private val sumsUrl = "https://example.invalid/SHA256SUMS"
    private val apkUrl = "https://example.invalid/openshm-patcher-0.1.0-r9.apk"
    private val hash = "ab".repeat(32)

    private fun release(
        tag: String = "patcher-v0.1.0-r9",
        apk: Boolean = true,
        sums: Boolean = true,
        body: String = "Fixes things.",
    ): ByteArray {
        val assets = buildList {
            if (apk) add("""{"name":"openshm-patcher-0.1.0-r9.apk","browser_download_url":"$apkUrl","size":12345}""")
            if (sums) add("""{"name":"SHA256SUMS","browser_download_url":"$sumsUrl","size":100}""")
        }.joinToString(",")
        return """{"tag_name":"$tag","body":"$body","assets":[$assets]}""".toByteArray()
    }

    private fun fetcher(release: ByteArray, sumsText: String? = "$hash  openshm-patcher-0.1.0-r9.apk\n") =
        FakeFetcher(buildMap {
            put(apiUrl, release)
            sumsText?.let { put(sumsUrl, it.toByteArray()) }
        })

    private fun check(f: CatalogueFetcher, versionCode: Long = 6, repo: String = "owner/name") = AppUpdater(repo, versionCode, f).check()

    @Test fun aNewerBuildIsOfferedWithItsHashAndLink() {
        val result = check(fetcher(release())) as AppUpdateResult.Available
        assertEquals(9L, result.update.build)
        assertEquals("0.1.0", result.update.versionName)
        assertEquals(apkUrl, result.update.apkUrl)
        assertEquals("openshm-patcher-0.1.0-r9.apk", result.update.apkName)
        assertEquals(hash, result.update.sha256)
        assertEquals("Fixes things.", result.update.notes)
        assertEquals(12345L, result.update.sizeBytes)
    }

    @Test fun theSameOrAnOlderBuildIsUpToDate() {
        assertEquals(AppUpdateResult.UpToDate, check(fetcher(release()), versionCode = 9))
        assertEquals(AppUpdateResult.UpToDate, check(fetcher(release()), versionCode = 12))
    }

    @Test fun anEmptyRepositoryIsDisabledAndMakesNoRequest() {
        val f = fetcher(release())
        assertEquals(AppUpdateResult.Disabled, check(f, repo = ""))
        assertTrue(f.requests.isEmpty())
    }

    @Test fun anUnrelatedTagIsUpToDate() {
        assertEquals(AppUpdateResult.UpToDate, check(fetcher(release(tag = "v2.0"))))
        assertEquals(AppUpdateResult.UpToDate, check(fetcher(release(tag = "catalogue-9"))))
    }

    @Test fun aMissingApkOrSumsFileFails() {
        assertTrue(check(fetcher(release(apk = false))) is AppUpdateResult.Failed)
        assertTrue(check(fetcher(release(sums = false))) is AppUpdateResult.Failed)
    }

    @Test fun aSumsFileWithoutTheApkLineOrWithABadHashFails() {
        assertTrue(check(fetcher(release(), "$hash  other.apk\n")) is AppUpdateResult.Failed)
        assertTrue(check(fetcher(release(), "nothex  openshm-patcher-0.1.0-r9.apk\n")) is AppUpdateResult.Failed)
        assertTrue(check(fetcher(release(), "abcd  openshm-patcher-0.1.0-r9.apk\n")) is AppUpdateResult.Failed)
        assertTrue(check(fetcher(release(), null)) is AppUpdateResult.Failed)
    }

    @Test fun malformedOrOversizeJsonFails() {
        assertTrue(check(fetcher("{not json".toByteArray())) is AppUpdateResult.Failed)
        assertTrue(check(fetcher("[1,2]".toByteArray())) is AppUpdateResult.Failed)
        assertTrue(check(fetcher("""{"assets":[]}""".toByteArray())) is AppUpdateResult.Failed)
        assertTrue(check(fetcher(ByteArray(AppUpdater.MAX_RELEASE_BYTES + 1) { ' '.code.toByte() })) is AppUpdateResult.Failed)
    }

    @Test fun aNetworkErrorFails() {
        assertTrue(check(FakeFetcher(error = IOException("timeout"))) is AppUpdateResult.Failed)
    }

    @Test fun anInvalidRepositoryNeverReachesTheNetwork() {
        val f = fetcher(release())
        for (repo in listOf("bad repo", "../evil", "owner/..", "a/b/c")) {
            assertTrue(repo, check(f, repo = repo) is AppUpdateResult.Failed)
        }
        assertTrue(f.requests.isEmpty())
    }

    @Test fun sumsLinesAcceptTheBinaryMarker() {
        assertEquals(hash, AppUpdater.hashFor("a.apk", "${hash.uppercase()} *a.apk\n"))
    }

    // The hourly limit and the switch.

    private fun checker(settings: MemSettings, now: () -> Long, repo: String = "owner/name", calls: IntArray = IntArray(1)) =
        AppUpdateChecker(repo, settings, { calls[0]++; AppUpdateResult.UpToDate }, now)

    @Test fun theStartupCheckIsSkippedWithinAnHour() {
        val calls = IntArray(1)
        val settings = MemSettings(lastCheckMillis = 1_000_000)
        var now = 1_000_000L + 59 * 60_000
        val c = checker(settings, { now }, calls = calls)
        assertNull(c.checkAtStartup())
        assertEquals(0, calls[0])
        now = 1_000_000L + 60 * 60_000
        assertEquals(AppUpdateResult.UpToDate, c.checkAtStartup())
        assertEquals(1, calls[0])
        assertEquals(now, settings.lastCheckMillis)
    }

    @Test fun theStartupCheckIsSkippedWhenTheSwitchIsOffOrTheBuildHasNoRepository() {
        val calls = IntArray(1)
        assertNull(checker(MemSettings(autoCheck = false), { 10_000_000_000 }, calls = calls).checkAtStartup())
        assertNull(checker(MemSettings(), { 10_000_000_000 }, repo = "", calls = calls).checkAtStartup())
        assertEquals(0, calls[0])
    }

    @Test fun aClockBehindTheLastCheckDoesNotBlockTheCheckForever() {
        val settings = MemSettings(lastCheckMillis = 9_000_000_000)
        assertEquals(AppUpdateResult.UpToDate, checker(settings, { 1_000 }).checkAtStartup())
    }

    @Test fun aManualCheckIgnoresTheSwitchAndTheLimit() {
        val calls = IntArray(1)
        val settings = MemSettings(autoCheck = false, lastCheckMillis = 5_000)
        val c = checker(settings, { 6_000 }, calls = calls)
        assertEquals(AppUpdateResult.UpToDate, c.checkNow())
        assertEquals(1, calls[0])
        assertEquals(6_000L, settings.lastCheckMillis)
    }

    @Test fun aDisabledBuildIsNeverStamped() {
        val settings = MemSettings()
        val c = AppUpdateChecker("", settings, { AppUpdateResult.Disabled }, { 5_000 })
        assertEquals(AppUpdateResult.Disabled, c.checkNow())
        assertEquals(0L, settings.lastCheckMillis)
    }

    // The background job: who gets a notification and when the job exists.

    private val update9 = io.openshm.patcher.data.AppUpdate(9, "0.1.0", apkUrl, "a.apk", hash, "", 1)

    @Test fun onlyANewBuildWithTheSwitchOnIsNotified() {
        val available = AppUpdateResult.Available(update9)
        assertTrue(io.openshm.patcher.data.shouldNotify(available, true, 8))
        assertFalse(io.openshm.patcher.data.shouldNotify(available, true, 9))
        assertFalse(io.openshm.patcher.data.shouldNotify(available, true, 10))
        assertFalse(io.openshm.patcher.data.shouldNotify(available, false, 0))
        assertFalse(io.openshm.patcher.data.shouldNotify(AppUpdateResult.UpToDate, true, 0))
        assertFalse(io.openshm.patcher.data.shouldNotify(AppUpdateResult.Failed("x"), true, 0))
        assertFalse(io.openshm.patcher.data.shouldNotify(AppUpdateResult.Disabled, true, 0))
    }

    @Test fun theBackgroundCheckNotifiesOnceAndRetriesOnFailure() {
        val settings = MemSettings()
        var result: AppUpdateResult = AppUpdateResult.Available(update9)
        val c = AppUpdateChecker("owner/name", settings, { result }, { 7_000 })
        assertEquals(io.openshm.patcher.data.BackgroundOutcome.Notify(update9), c.checkInBackground())
        assertEquals(7_000L, settings.lastCheckMillis)
        c.markNotified(9)
        assertEquals(io.openshm.patcher.data.BackgroundOutcome.Done, c.checkInBackground())
        result = AppUpdateResult.Failed("offline")
        assertEquals(io.openshm.patcher.data.BackgroundOutcome.Retry, c.checkInBackground())
        settings.autoCheck = false
        result = AppUpdateResult.Available(update9.copy(build = 10))
        assertEquals(io.openshm.patcher.data.BackgroundOutcome.Done, c.checkInBackground())
    }

    private class FakeJob(var scheduled: Boolean) : io.openshm.patcher.data.UpdateJobPort {
        var schedules = 0
        var cancels = 0
        override fun isScheduled() = scheduled
        override fun schedule() { scheduled = true; schedules++ }
        override fun cancel() { scheduled = false; cancels++ }
    }

    @Test fun theJobExistsOnlyWhileTheSwitchIsOnAndTheBuildHasARepository() {
        val none = FakeJob(false)
        io.openshm.patcher.data.syncUpdateJob(true, "owner/name", none)
        assertEquals(1, none.schedules)
        val pending = FakeJob(true)
        io.openshm.patcher.data.syncUpdateJob(true, "owner/name", pending)
        assertEquals(0, pending.schedules)
        io.openshm.patcher.data.syncUpdateJob(false, "owner/name", pending)
        assertEquals(1, pending.cancels)
        val noRepo = FakeJob(true)
        io.openshm.patcher.data.syncUpdateJob(true, "", noRepo)
        assertEquals(1, noRepo.cancels)
        val idle = FakeJob(false)
        io.openshm.patcher.data.syncUpdateJob(false, "owner/name", idle)
        assertEquals(0, idle.cancels + idle.schedules)
    }

    // The hash of the downloaded file.

    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("app-update").toFile() }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun theDownloadedFileMustMatchThePublishedHash() {
        val file = File(dir, "a.apk").apply { writeBytes("payload".toByteArray()) }
        val good = MessageDigest.getInstance("SHA-256").digest("payload".toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(fileHasSha256(file, good))
        assertTrue(fileHasSha256(file, good.uppercase()))
        assertFalse(fileHasSha256(file, hash))
        file.writeBytes("tampered".toByteArray())
        assertFalse(fileHasSha256(file, good))
    }
}
