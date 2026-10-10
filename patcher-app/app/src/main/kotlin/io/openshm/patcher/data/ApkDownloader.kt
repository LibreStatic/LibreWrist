package io.openshm.patcher.data

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * A download the WebView handed over. [cookie] is what the WebView holds for [url]; it is only ever sent
 * to APKMirror hosts. [toString] redacts everything, since URLs and cookies can carry session data.
 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val cookie: String? = null,
) {
    override fun toString() = "DownloadRequest(length=$contentLength)"
}

/**
 * Fetches a [DownloadRequest] over https with [HttpURLConnection] and streams the body into [fetch]'s
 * consumer (the shared input verification). Redirects are followed by hand, up to [MAX_REDIRECTS], and
 * every hop must be https. The test seams ([insecureHosts], [cookieHost]) default to the production rule.
 */
class ApkDownloader internal constructor(
    private val insecureHosts: Set<String> = emptySet(),
    private val cookieHost: (String?) -> Boolean = ApkMirrorGuide::isApkMirrorHost,
    private val maxBytes: Long = MAX_BYTES,
) {
    constructor() : this(emptySet())

    /**
     * Opens the file and runs [consume] on the body (stream, content length or -1). Failures come back as
     * a rejection; [consume] owns verification and any temp file. [onOpen] receives the live connection's
     * closer so the caller can abort it on cancellation.
     */
    fun fetch(
        request: DownloadRequest,
        onOpen: (close: () -> Unit) -> Unit = {},
        consume: (InputStream, Long) -> ImportResult,
    ): ImportResult {
        var connection: HttpURLConnection? = null
        var body: LimitedInputStream? = null
        try {
            connection = open(request)
            val conn = connection
            onOpen { conn.disconnect() }
            val length = conn.contentLengthLong
            if (length > maxBytes) return ImportResult.Rejected(RejectReason.TooLarge)
            val limited = LimitedInputStream(conn.inputStream, maxBytes)
            body = limited
            val result = consume(limited, length)
            return if (limited.exceeded) ImportResult.Rejected(RejectReason.TooLarge) else result
        } catch (e: IOException) {
            return ImportResult.Rejected(if (body?.exceeded == true) RejectReason.TooLarge else RejectReason.DownloadFailed)
        } finally {
            connection?.disconnect()
        }
    }

    private fun open(request: DownloadRequest): HttpURLConnection {
        var url = parse(request.url)
        var cookie = request.cookie
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(url)) throw IOException("not https")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                request.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                if (cookie != null && cookieHost(url.host)) conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty("Accept-Encoding", "identity")
                val code = conn.responseCode
                if (code in REDIRECTS) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without target")
                    val next = runCatching { URI(url.toString()).resolve(location).toURL() }.getOrNull()
                        ?: throw IOException("bad redirect")
                    conn.disconnect()
                    // A hop to another host must not carry the cookie even if it is allowed there later.
                    if (!next.host.equals(url.host, ignoreCase = true)) cookie = null
                    url = next
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("http $code")
                return conn
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
        }
        throw IOException("too many redirects")
    }

    private fun parse(url: String): URL = runCatching { URI(url).toURL() }.getOrElse { throw IOException("bad url") }

    private fun allowed(url: URL): Boolean =
        url.protocol == "https" && url.userInfo == null || url.protocol == "http" && url.host in insecureHosts

    private class LimitedInputStream(source: InputStream, private val limit: Long) : FilterInputStream(source) {
        private var count = 0L

        /** Set once the body went past the cap; the read that crossed it fails. */
        var exceeded = false
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > limit) {
                exceeded = true
                throw IOException("over the size cap")
            }
        }
    }

    companion object {
        const val MAX_BYTES = 400L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 30_000
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
