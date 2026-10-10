package io.openshm.patcher

import com.sun.net.httpserver.HttpServer
import io.openshm.patcher.data.ApkDownloader
import io.openshm.patcher.data.DownloadRequest
import io.openshm.patcher.data.ImportResult
import io.openshm.patcher.data.RejectReason
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs against a local plain-http server through the test-only insecure-host seam; production is https only. */
class ApkDownloaderTest {
    private lateinit var server: HttpServer
    private val seenCookies = mutableMapOf<String, String?>()
    private val seenAgents = mutableListOf<String?>()
    private val payload = ByteArray(300_000) { it.toByte() }
    private val port get() = server.address.port

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            seenCookies["file"] = ex.requestHeaders.getFirst("Cookie")
            seenAgents += ex.requestHeaders.getFirst("User-Agent")
            ex.sendResponseHeaders(200, payload.size.toLong())
            ex.responseBody.use { it.write(payload) }
        }
        server.createContext("/hop") { ex ->
            val n = ex.requestURI.query.toInt()
            ex.responseHeaders.add("Location", if (n == 0) "/file" else "/hop?${n - 1}")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/to-https") { ex ->
            ex.responseHeaders.add("Location", "https://127.0.0.1:1/x")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/to-other-host") { ex ->
            ex.responseHeaders.add("Location", "http://localhost:$port/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/missing") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.createContext("/chunked") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> repeat(10) { out.write(payload) } }
        }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
    }

    private fun downloader(maxBytes: Long = ApkDownloader.MAX_BYTES, cookieHosts: Set<String> = setOf("127.0.0.1", "localhost")) =
        ApkDownloader(setOf("127.0.0.1", "localhost"), { it in cookieHosts }, maxBytes)

    private fun request(path: String, cookie: String? = "a=b") =
        DownloadRequest("http://127.0.0.1:$port$path", "UA/1", null, null, -1, cookie)

    private fun run(d: ApkDownloader, path: String, cookie: String? = "a=b"): Pair<ImportResult, ByteArray?> {
        var got: ByteArray? = null
        val result = d.fetch(request(path, cookie)) { stream, _ ->
            got = stream.readBytes()
            ImportResult.Verified(got!!.size.toLong())
        }
        return result to got
    }

    @Test fun streamsTheBodyWithHeaders() {
        val (result, body) = run(downloader(), "/file")
        assertEquals(ImportResult.Verified(payload.size.toLong()), result)
        assertTrue(body!!.contentEquals(payload))
        assertEquals("a=b", seenCookies["file"])
        assertEquals("UA/1", seenAgents.single())
    }

    @Test fun followsUpToFiveRedirects() {
        assertEquals(ImportResult.Verified(payload.size.toLong()), run(downloader(), "/hop?4").first)
    }

    @Test fun moreThanFiveRedirectsFail() {
        assertEquals(ImportResult.Rejected(RejectReason.DownloadFailed), run(downloader(), "/hop?5").first)
    }

    @Test fun aRedirectToHttpsOfAnUnreachableHostFailsAndPlainHttpIsRefusedInProduction() {
        assertEquals(ImportResult.Rejected(RejectReason.DownloadFailed), run(downloader(), "/to-https").first)
        val production = ApkDownloader()
        val result = production.fetch(request("/file")) { _, _ -> error("must not read") }
        assertEquals(ImportResult.Rejected(RejectReason.DownloadFailed), result)
        assertNull(seenCookies["file"])
    }

    @Test fun cookiesAreNotSentToAnotherHostOrToHostsWithoutPermission() {
        run(downloader(), "/to-other-host")
        assertNull(seenCookies["file"])
        seenCookies.clear()
        run(downloader(cookieHosts = emptySet()), "/file")
        assertNull(seenCookies["file"])
    }

    @Test fun httpErrorsFail() {
        assertEquals(ImportResult.Rejected(RejectReason.DownloadFailed), run(downloader(), "/missing").first)
    }

    @Test fun aKnownLengthOverTheCapIsRefusedBeforeReading() {
        var read = false
        val result = downloader(maxBytes = 1000).fetch(request("/file")) { _, _ -> read = true; ImportResult.Verified(0) }
        assertEquals(ImportResult.Rejected(RejectReason.TooLarge), result)
        assertTrue(!read)
    }

    @Test fun anUnknownLengthIsCutOffAtTheCap() {
        val (result, _) = run(downloader(maxBytes = 1_000_000), "/chunked")
        assertEquals(ImportResult.Rejected(RejectReason.TooLarge), result)
    }

    @Test fun theRequestDoesNotPrintItsUrlOrCookie() {
        val text = request("/file?token=secret", "session=secret").toString()
        assertTrue(!text.contains("secret") && !text.contains("127.0.0.1"))
    }
}
