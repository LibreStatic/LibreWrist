package io.openshm.patcher.data

/**
 * The rules of one place the guided browser can fetch an input APK from (APKMirror, YingYongBao). The
 * WebView, the redirect chain and the coach UI only talk to this interface. No Android types, so every
 * implementation is unit-tested on the JVM.
 */
interface SiteGuide {
    /** The page the browser opens first for [input]. */
    fun startUrl(input: InputApp): String

    /** True for a page the WebView may load; everything else is blocked. */
    fun isAllowedNavigation(url: String): Boolean

    /** True for a host that serves pages of this source (as opposed to a download CDN). */
    fun hostIsSource(host: String?): Boolean

    /** The step of the walk-through that the page at [url] is. */
    fun coachStep(url: String, input: InputApp): CoachState

    /** Whether a download the WebView started may be fetched. */
    fun isAcceptableDownload(url: String, contentDisposition: String?, mimeType: String?): DownloadVerdict

    /** True when a file may be fetched from [url]'s host without having been an accepted redirect hop. */
    fun mayDownloadFrom(url: String): Boolean

    /** The URL to fetch for a download link, e.g. an http link upgraded to https; identity when nothing applies. */
    fun normalizeDownloadUrl(url: String): String

    /** An off-site hop of a server redirect that follows a page of this source (a CDN hand-off). */
    fun acceptsRedirectHop(url: String, isRedirect: Boolean, chainFromSource: Boolean): Boolean

    /** True for a link that points straight at the file (not a page and not a redirect); default none. */
    fun isDirectDownloadLink(url: String): Boolean = false
}

/**
 * Where the user can get an input APK from in the guided browser. [arg] is the navigation argument.
 * The order in which [InputApp.sources] lists them decides which one is the primary button; China
 * mode changes that order.
 */
enum class InputSource(val arg: String, val guide: SiteGuide) {
    ApkMirror("apkmirror", ApkMirrorGuide),
    Yyb("yyb", QqGuide),
    ;

    companion object {
        fun fromArg(arg: String?): InputSource = entries.firstOrNull { it.arg == arg } ?: ApkMirror
    }
}

/**
 * The sources [this] input is published on, primary first: APKMirror, then YingYongBao; in
 * [chinaMode] YingYongBao goes first.
 */
fun InputApp.sources(chinaMode: Boolean = false): List<InputSource> = buildList {
    if (onApkMirror) add(InputSource.ApkMirror)
    if (onYyb) add(InputSource.Yyb)
    if (chinaMode && onYyb) reverse()
}
