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
object ApkMirrorGuide : SiteGuide {
    const val HOST = "apkmirror.com"
    private const val RELEASE_SUFFIX = "-release"
    private const val APP_SEGMENTS = 3 // apk / <developer> / <app>
    private val BUNDLE_EXTENSIONS = listOf(".apkm", ".xapk", ".apks", ".zip")

    /** The release page of the exact version when the catalogue knows it, else the app's page. */
    override fun startUrl(input: InputApp): String {
        val base = "https://www.$HOST/apk/${input.apkMirrorSlug.trim('/')}/"
        val release = input.apkMirrorRelease?.trim('/')?.takeIf { it.isNotEmpty() && '/' !in it }
        return if (release != null) "$base$release/" else base
    }

    /**
     * An off-APKMirror hop of a server redirect (APKMirror hands the file to a CDN this way): https,
     * no credentials, default port, a real redirect, and a chain that started on APKMirror.
     */
    fun isAcceptableRedirectHop(url: String, isRedirect: Boolean, chainFromApkMirror: Boolean): Boolean {
        if (!isRedirect || !chainFromApkMirror) return false
        val uri = parse(url) ?: return false
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            !uri.host.isNullOrEmpty()
    }

    /** The host of an https URL, lowercase, or null. */
    fun hostOf(url: String): String? = parse(url)?.host?.lowercase(Locale.ROOT)?.trimEnd('.')

    /** True for apkmirror.com and its subdomains, case-insensitively. */
    fun isApkMirrorHost(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    /** An https URL on APKMirror without credentials; everything else is not loaded in the WebView. */
    override fun isAllowedNavigation(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            isApkMirrorHost(uri.host)
    }

    override fun coachStep(url: String, input: InputApp): CoachState {
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

    override fun isAcceptableDownload(url: String, contentDisposition: String?, mimeType: String?): DownloadVerdict {
        val uri = parse(url)
        if (uri == null || uri.scheme != "https" || uri.userInfo != null) return DownloadVerdict.NotHttps
        return if (isBundle(uri, contentDisposition, mimeType)) DownloadVerdict.Bundle else DownloadVerdict.Ok
    }

    /** True for a bundle (.apkm, .xapk, .apks, .zip) by its file name or MIME type; shared by every source. */
    internal fun isBundle(uri: URI, contentDisposition: String?, mimeType: String?): Boolean {
        val names = listOfNotNull(
            fileNameFromDisposition(contentDisposition),
            uri.rawPath?.substringAfterLast('/'),
        ).map { it.lowercase(Locale.ROOT).trimEnd('"', ' ', ';') }
        if (names.any { name -> BUNDLE_EXTENSIONS.any { name.endsWith(it) } }) return true
        return mimeType?.lowercase(Locale.ROOT)?.contains("apkm") == true
    }

    override fun hostIsSource(host: String?): Boolean = isApkMirrorHost(host)

    override fun mayDownloadFrom(url: String): Boolean = isApkMirrorHost(hostOf(url))

    override fun normalizeDownloadUrl(url: String): String = url

    override fun acceptsRedirectHop(url: String, isRedirect: Boolean, chainFromSource: Boolean): Boolean =
        isAcceptableRedirectHop(url, isRedirect, chainFromSource)

    internal fun fileNameFromDisposition(disposition: String?): String? {
        if (disposition == null) return null
        val star = Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        val plain = Regex("""filename\s*=\s*("([^"]*)"|[^;]+)""", RegexOption.IGNORE_CASE).find(disposition)
        return (star?.groupValues?.get(1) ?: plain?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } })?.trim()
    }

    internal fun parse(url: String): URI? {
        if (url.contains('\\')) return null
        return runCatching { URI(url.trim()) }.getOrNull()
    }
}

/** What to do with a main-frame navigation. */
enum class NavDecision { Allow, AllowRedirectHop, Block }

/**
 * Tracks one navigation chain of the guided browser for one [SiteGuide]: an off-site redirect hop is
 * only accepted while the chain began on a page of the source, and a download from such a host only if
 * that host was the accepted hop. No Android types.
 */
class RedirectChain(private val guide: SiteGuide = ApkMirrorGuide) {
    private var fromSource = false
    private var hopHost: String? = null

    /** Called for every main-frame navigation request. */
    fun onNavigation(url: String, isRedirect: Boolean): NavDecision {
        if (guide.isAllowedNavigation(url)) {
            fromSource = true
            hopHost = null
            return NavDecision.Allow
        }
        if (guide.isDirectDownloadLink(url)) return NavDecision.Allow
        if (guide.acceptsRedirectHop(url, isRedirect, fromSource)) {
            hopHost = ApkMirrorGuide.hostOf(url)
            return NavDecision.AllowRedirectHop
        }
        return NavDecision.Block
    }

    /** True when [url] is on the source, a download host of it, or the host the chain was redirected to. */
    fun mayDownloadFrom(url: String): Boolean {
        val host = ApkMirrorGuide.hostOf(url) ?: return false
        return guide.mayDownloadFrom(guide.normalizeDownloadUrl(url)) || (hopHost != null && host == hopHost)
    }

    /** An accepted off-site hop rendered as a page: the chain is over. */
    fun onOffSitePage() {
        fromSource = false
        hopHost = null
    }

    /** True for a URL that is an accepted hop host rather than the source (a page, not a download). */
    fun isHopHost(url: String): Boolean {
        val host = ApkMirrorGuide.hostOf(url) ?: return false
        return hopHost != null && host == hopHost && !guide.hostIsSource(host)
    }
}
