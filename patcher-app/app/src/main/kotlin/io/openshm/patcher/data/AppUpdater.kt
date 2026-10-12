package io.openshm.patcher.data

import io.openshm.patcher.engine.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A newer patcher build published as a release. */
data class AppUpdate(
    val build: Long,
    val versionName: String,
    val apkUrl: String,
    val apkName: String,
    /** Lower-case hex SHA-256 of the APK, from the release's `SHA256SUMS`. */
    val sha256: String,
    val notes: String,
    val sizeBytes: Long,
)

/** Outcome of one check of the release channel. */
sealed interface AppUpdateResult {
    data class Available(val update: AppUpdate) : AppUpdateResult
    data object UpToDate : AppUpdateResult
    /** The release was understood but cannot be offered, or the network failed. */
    data class Failed(val reason: String) : AppUpdateResult
    /** No update repository is configured in this build; nothing was requested. */
    data object Disabled : AppUpdateResult
}

/**
 * Asks the GitHub Releases API of [repo] (`owner/name`) for the latest release and decides whether it
 * is a newer patcher build than [versionCode]. The release tag is `patcher-v<ver>-r<N>`, where N is the
 * build number (CI sets versionCode to it); it must carry the APK `openshm-patcher-<ver>-r<N>.apk` and
 * a `SHA256SUMS` listing it, otherwise nothing is offered. Pure logic over [CatalogueFetcher].
 */
class AppUpdater(
    private val repo: String,
    private val versionCode: Long,
    private val fetcher: CatalogueFetcher,
) {
    fun check(): AppUpdateResult {
        if (repo.isBlank()) return AppUpdateResult.Disabled
        if (!REPO.matches(repo)) return AppUpdateResult.Failed("invalid update repository")
        val release = try {
            val bytes = fetcher.get("https://api.github.com/repos/$repo/releases/latest", MAX_RELEASE_BYTES)
            Json.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *> ?: return AppUpdateResult.Failed("unexpected release data")
        } catch (e: IOException) {
            return AppUpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: Json.ParseException) {
            return AppUpdateResult.Failed("unreadable release data")
        }
        val tag = release["tag_name"] as? String ?: return AppUpdateResult.Failed("release without a tag")
        val match = TAG.matchEntire(tag) ?: return AppUpdateResult.UpToDate
        val versionName = match.groupValues[1]
        val build = match.groupValues[2].toLongOrNull() ?: return AppUpdateResult.Failed("bad build number")
        if (build <= versionCode) return AppUpdateResult.UpToDate

        val assets = (release["assets"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val apkName = "openshm-patcher-$versionName-r$build.apk"
        val apk = assets.firstOrNull { it["name"] == apkName } ?: return AppUpdateResult.Failed("the release has no $apkName")
        val sums = assets.firstOrNull { it["name"] == "SHA256SUMS" } ?: return AppUpdateResult.Failed("the release has no SHA256SUMS")
        val apkUrl = apk["browser_download_url"] as? String ?: return AppUpdateResult.Failed("the APK has no download link")
        val sumsUrl = sums["browser_download_url"] as? String ?: return AppUpdateResult.Failed("SHA256SUMS has no download link")
        val size = (apk["size"] as? Long)?.takeIf { it > 0 } ?: return AppUpdateResult.Failed("the APK has no size")
        val hash = try {
            hashFor(apkName, String(fetcher.get(sumsUrl, MAX_SUMS_BYTES), Charsets.UTF_8))
        } catch (e: IOException) {
            return AppUpdateResult.Failed(e.message ?: e.javaClass.simpleName)
        } ?: return AppUpdateResult.Failed("SHA256SUMS does not list $apkName")
        return AppUpdateResult.Available(
            AppUpdate(build, versionName, apkUrl, apkName, hash, (release["body"] as? String).orEmpty().trim(), size),
        )
    }

    companion object {
        const val MAX_RELEASE_BYTES = 1024 * 1024
        const val MAX_SUMS_BYTES = 16 * 1024
        // Dot-only segments ("..") would walk out of the API path.
        private val REPO = Regex("""^(?!\.+/)[\w.-]+/(?!\.+$)[\w.-]+$""")
        private val TAG = Regex("""^patcher-v([\w.]+)-r(\d+)$""")
        private val SUM_LINE = Regex("""^([0-9a-fA-F]{64}) [ *](\S+)$""")

        /** The hash listed for [name] in a `sha256sum`-style file; null when absent or malformed. */
        internal fun hashFor(name: String, sums: String): String? =
            sums.lineSequence().map { it.trim() }.firstNotNullOfOrNull { line ->
                SUM_LINE.matchEntire(line)?.takeIf { it.groupValues[2] == name }?.groupValues?.get(1)?.lowercase()
            }
    }
}

/** What the update check remembers between runs. */
interface AppUpdateSettings {
    /** Check at startup (default on). */
    var autoCheck: Boolean
    var lastCheckMillis: Long
    /** The highest build a notification was posted for, so a version is announced once. */
    var lastNotifiedBuild: Long
    /** The newer build the last check found, offered again without a request until the next check is due. */
    var cachedUpdate: AppUpdate?
    /** A build the user asked not to be shown again at startup or in a notification (0: none). */
    var skippedBuild: Long
}

/** What the periodic background check asks of its job. */
sealed interface BackgroundOutcome {
    /** Nothing to do; the job is finished. */
    data object Done : BackgroundOutcome
    /** The network failed: let the scheduler retry with backoff. */
    data object Retry : BackgroundOutcome
    /** A newer build the user was not told about yet. */
    data class Notify(val update: AppUpdate) : BackgroundOutcome
}

/** Whether a check result earns a notification: a build newer than the last announced one, with the switch on. */
fun shouldNotify(result: AppUpdateResult, autoCheck: Boolean, lastNotifiedBuild: Long): Boolean =
    autoCheck && result is AppUpdateResult.Available && result.update.build > lastNotifiedBuild

/** Whether the periodic job should exist: the switch is on and this build has an update repository. */
fun shouldSchedule(autoCheck: Boolean, repo: String): Boolean = autoCheck && repo.isNotBlank()

/** The platform job scheduler, behind a seam for tests. */
interface UpdateJobPort {
    fun isScheduled(): Boolean
    fun schedule()
    fun cancel()
}

/** Makes the job match the setting; an identical pending job is left alone so its period is not reset. */
fun syncUpdateJob(autoCheck: Boolean, repo: String, port: UpdateJobPort) {
    if (shouldSchedule(autoCheck, repo)) {
        if (!port.isScheduled()) port.schedule()
    } else if (port.isScheduled()) {
        port.cancel()
    }
}

/**
 * When to ask the release channel: at startup only when the user allows it, a repository is set and
 * the last attempt is at least [MIN_INTERVAL_MS] old (GitHub limits anonymous calls per hour); a manual
 * check always runs. Each attempt that reaches the network is stamped, failures included.
 */
class AppUpdateChecker(
    private val repo: String,
    private val settings: AppUpdateSettings,
    private val updater: () -> AppUpdateResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val versionCode: Long = 0L,
) {
    val enabled: Boolean get() = repo.isNotBlank()

    /**
     * The startup check: a request when due, otherwise the newer build the last check found (no request).
     * Null when there is nothing to offer. A build the user skipped is never returned.
     */
    fun checkAtStartup(): AppUpdateResult? {
        if (!enabled || !settings.autoCheck) return null
        val now = clock()
        val last = settings.lastCheckMillis
        val due = now - last >= MIN_INTERVAL_MS || last > now
        val result = if (due) run(now) else cached()?.let { AppUpdateResult.Available(it) }
        return result.takeUnless { it is AppUpdateResult.Available && it.update.build == settings.skippedBuild }
    }

    /** "Don't show again" for [build]: no startup offer or notification for it; a later build is shown. */
    fun skip(build: Long) {
        settings.skippedBuild = build
    }

    /** The cached newer build; a cache this app has already reached (it was updated) is dropped. */
    private fun cached(): AppUpdate? {
        val update = settings.cachedUpdate ?: return null
        if (update.build > versionCode) return update
        settings.cachedUpdate = null
        return null
    }

    /** Ignores the switch and the interval. */
    fun checkNow(): AppUpdateResult = run(clock())

    /** The periodic job's check: honours the switch, shares the timestamp with the startup check. */
    fun checkInBackground(): BackgroundOutcome {
        if (!enabled || !settings.autoCheck) return BackgroundOutcome.Done
        val result = run(clock())
        return when {
            result is AppUpdateResult.Failed -> BackgroundOutcome.Retry
            result is AppUpdateResult.Available && result.update.build != settings.skippedBuild &&
                shouldNotify(result, settings.autoCheck, settings.lastNotifiedBuild) ->
                BackgroundOutcome.Notify(result.update)
            else -> BackgroundOutcome.Done
        }
    }

    /** Call after the notification for [build] was posted. */
    fun markNotified(build: Long) {
        settings.lastNotifiedBuild = build
    }

    private fun run(now: Long): AppUpdateResult {
        val result = updater()
        if (result != AppUpdateResult.Disabled) settings.lastCheckMillis = now
        when (result) {
            is AppUpdateResult.Available -> settings.cachedUpdate = result.update
            AppUpdateResult.UpToDate -> settings.cachedUpdate = null
            else -> Unit
        }
        return result
    }

    companion object {
        const val MIN_INTERVAL_MS = 60L * 60 * 1000
    }
}

/** Whether [file] has the SHA-256 [expectedHex] (case-insensitive). */
fun fileHasSha256(file: File, expectedHex: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.equals(expectedHex.trim(), ignoreCase = true)
}
