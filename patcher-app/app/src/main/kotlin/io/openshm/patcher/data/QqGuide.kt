package io.openshm.patcher.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** The `fsname` hint of a YingYongBao CDN link (`<package>_<versionName>.apk`); display only, never verification. */
data class QqFileName(val packageName: String, val versionName: String)

/**
 * Pure rules for YingYongBao (Tencent's app store, `sj.qq.com`). The mobile page `a.app.qq.com/o/simple.jsp`
 * embeds a plain link to the CDN (`imtt[N].dd.qq.com/.../<32 hex>.apk`, http) which the WebView turns into
 * a download. The catalogue sha256 is the only version gate. No Android types.
 */
object QqGuide : SiteGuide {
    const val APP_HOST = "a.app.qq.com"
    const val SITE_HOST = "sj.qq.com"
    private val CDN_HOST = Regex("""(^|\.)imtt\d*\.dd\.qq\.com$""")
    private val CDN_ANY_HOST = Regex("""(^|\.)dd\.qq\.com$""")
    private val CDN_FILE = Regex("""^[0-9A-Fa-f]{32}\.apk$""")
    private val FS_NAME = Regex("""^(.+)_(\d[^_]*)\.apk$""")

    override fun startUrl(input: InputApp): String = "https://$APP_HOST/o/simple.jsp?pkgname=${input.packageName}"

    override fun hostIsSource(host: String?): Boolean {
        val h = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return h == APP_HOST || h == SITE_HOST
    }

    override fun isAllowedNavigation(url: String): Boolean {
        val uri = ApkMirrorGuide.parse(url) ?: return false
        return uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            hostIsSource(uri.host)
    }

    /** 1 on the app's page, 2 on any other allowed page; there is no version parsing, the sha256 decides. */
    override fun coachStep(url: String, input: InputApp): CoachState {
        val uri = ApkMirrorGuide.parse(url)?.takeIf { isAllowedNavigation(url) } ?: return CoachState(1)
        val onAppPage = uri.host.lowercase(Locale.ROOT).trimEnd('.') == APP_HOST &&
            queryParam(uri, "pkgname") == input.packageName
        return CoachState(if (onAppPage) 1 else 2)
    }

    override fun isAcceptableDownload(url: String, contentDisposition: String?, mimeType: String?): DownloadVerdict {
        val normalized = normalizeDownloadUrl(url)
        val uri = ApkMirrorGuide.parse(normalized)
        if (uri == null || uri.scheme != "https" || uri.userInfo != null || !mayDownloadFrom(normalized)) {
            return DownloadVerdict.NotHttps
        }
        return if (ApkMirrorGuide.isBundle(uri, contentDisposition, mimeType)) DownloadVerdict.Bundle else DownloadVerdict.Ok
    }

    /** The CDN serves the identical file over https; the embedded link is http. Only `*.dd.qq.com` is upgraded. */
    override fun normalizeDownloadUrl(url: String): String {
        val uri = ApkMirrorGuide.parse(url) ?: return url
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return url
        if (uri.scheme != "http" || uri.port != -1 || !CDN_ANY_HOST.containsMatchIn(host)) return url
        return "https" + url.trim().substring("http".length)
    }

    /** A whole-file CDN link: `imtt[N].dd.qq.com`, a 32-hex `.apk` name, not an `_offset_` partial mirror. */
    override fun mayDownloadFrom(url: String): Boolean {
        val uri = ApkMirrorGuide.parse(url) ?: return false
        if (uri.scheme != "https" && uri.scheme != "http") return false
        if (uri.userInfo != null || !(uri.port == -1 || uri.port == 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (!CDN_HOST.containsMatchIn(host)) return false
        val path = uri.rawPath.orEmpty()
        return "_offset_" !in path && CDN_FILE.matches(path.substringAfterLast('/'))
    }

    /** The main frame may navigate straight to a CDN file link (the page's download button). */
    override fun isDirectDownloadLink(url: String): Boolean = mayDownloadFrom(normalizeDownloadUrl(url))

    /** The CDN link is a direct navigation, never a redirect hop. */
    override fun acceptsRedirectHop(url: String, isRedirect: Boolean, chainFromSource: Boolean): Boolean = false

    /** The `fsname` query parameter of a CDN link, split into package and version; null when absent or odd. */
    fun parseFsName(url: String): QqFileName? {
        val uri = ApkMirrorGuide.parse(url) ?: return null
        val match = FS_NAME.matchEntire(queryParam(uri, "fsname") ?: return null) ?: return null
        return QqFileName(match.groupValues[1], match.groupValues[2])
    }

    private fun queryParam(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
            val key = pair.substringBefore('=')
            if (key == name && '=' in pair) runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull() else null
        }
}
