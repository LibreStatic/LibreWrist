package io.openshm.patcher.data

import java.net.URI
import java.util.Locale

/** What the browser coach shows for the page the WebView is on. */
data class CoachState(val step: Int, val wrongVersion: Boolean = false)

/** Whether a download the WebView started may be fetched. */
enum class DownloadVerdict { Ok, NotHttps, Bundle }

/**
 * Pure rules for the guided APKMirror browser: where the WebView may go, which step of the walk-through a
 * page is, and which downloads are worth fetching. No Android types, so it is unit-tested on the JVM.
 */
object ApkMirrorGuide {
    const val HOST = "apkmirror.com"
    private const val RELEASE_SUFFIX = "-release"
    private const val APP_SEGMENTS = 3 // apk / <developer> / <app>
    private val BUNDLE_EXTENSIONS = listOf(".apkm", ".xapk", ".apks", ".zip")

    fun startUrl(input: InputApp): String = "https://www.$HOST/apk/${input.apkMirrorSlug.trim('/')}/"

    /** True for apkmirror.com and its subdomains, case-insensitively. */
    fun isApkMirrorHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    /** An https URL on APKMirror without credentials; everything else is not loaded in the WebView. */
    fun isAllowedNavigation(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            isApkMirrorHost(uri.host)
    }

    fun coachStep(url: String, input: InputApp): CoachState {
        val uri = parse(url)?.takeIf { isAllowedNavigation(url) } ?: return CoachState(1)
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val app = ("apk/" + input.apkMirrorSlug.trim('/')).split('/')
        if (app.size != APP_SEGMENTS || segments.size <= APP_SEGMENTS) return CoachState(1)
        if (segments.take(APP_SEGMENTS).map { it.lowercase(Locale.ROOT) } != app) return CoachState(1)
        val release = segments[APP_SEGMENTS].lowercase(Locale.ROOT)
        if (!release.endsWith(RELEASE_SUFFIX)) return CoachState(1)
        val dashed = input.versionName.lowercase(Locale.ROOT).replace('.', '-')
        if (!Regex("(^|-)${Regex.escape(dashed)}(-|$)").containsMatchIn(release)) return CoachState(1, wrongVersion = true)
        return CoachState(if (segments.size > APP_SEGMENTS + 1) 3 else 2)
    }

    fun isAcceptableDownload(url: String, contentDisposition: String?, mimeType: String?): DownloadVerdict {
        val uri = parse(url)
        if (uri == null || uri.scheme != "https" || uri.userInfo != null) return DownloadVerdict.NotHttps
        val names = listOfNotNull(
            fileNameFromDisposition(contentDisposition),
            uri.rawPath?.substringAfterLast('/'),
        ).map { it.lowercase(Locale.ROOT).trimEnd('"', ' ', ';') }
        if (names.any { name -> BUNDLE_EXTENSIONS.any { name.endsWith(it) } }) return DownloadVerdict.Bundle
        if (mimeType?.lowercase(Locale.ROOT)?.contains("apkm") == true) return DownloadVerdict.Bundle
        return DownloadVerdict.Ok
    }

    internal fun fileNameFromDisposition(disposition: String?): String? {
        if (disposition == null) return null
        val star = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        val plain = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        return (star?.groupValues?.get(1) ?: plain?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } })?.trim()
    }

    private fun parse(url: String): URI? {
        if (url.contains('\\')) return null
        return runCatching { URI(url.trim()) }.getOrNull()
    }
}
