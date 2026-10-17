package io.openshm.patcher

import io.openshm.patcher.data.ApkMirrorGuide
import io.openshm.patcher.data.CoachState
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.DownloadVerdict
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.InputSource
import io.openshm.patcher.data.NavDecision
import io.openshm.patcher.data.QqFileName
import io.openshm.patcher.data.QqGuide
import io.openshm.patcher.data.RedirectChain
import io.openshm.patcher.data.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqGuideTest {
    private val input = InputApp(
        "shealth-phone", "Samsung Health", "7.00.6.012", Device.Phone, "0".repeat(64), "com.sec.android.app.shealth",
        "samsung-electronics-co-ltd/samsung-health", emptyList(), onYyb = true,
    )
    private val appPage = "https://a.app.qq.com/o/simple.jsp?pkgname=com.sec.android.app.shealth"
    private val cdn = "http://imtt.dd.qq.com/sjy.00022/sjy.00004/16891/apk/54477E5BA1DABFE2905A3BB3D54C06CD.apk" +
        "?fsname=com.sec.android.app.shealth_7.00.6.012.apk"
    private val cdnHttps = cdn.replaceFirst("http://", "https://")
    private val partial = "http://microdown.myapp.com/ug/20260922_138e411f6e3e894f928e4c89a6cb9cac_offset_32296960.apk"

    @Test fun startUrlIsTheMobilePage() {
        assertEquals(appPage, QqGuide.startUrl(input))
    }

    @Test fun allowListAcceptsOnlyHttpsQqPages() {
        assertTrue(QqGuide.isAllowedNavigation(appPage))
        assertTrue(QqGuide.isAllowedNavigation("https://sj.qq.com/appdetail/com.sec.android.app.shealth"))
        assertTrue(QqGuide.isAllowedNavigation("https://A.APP.qq.com:443/x"))
        listOf(
            "http://a.app.qq.com/o/simple.jsp",
            "https://a.app.qq.com.evil.tld/",
            "https://evila.app.qq.com/",
            "https://user:pw@a.app.qq.com/",
            "https://a.app.qq.com@evil.example/",
            "https://a.app.qq.com:8443/",
            "https://imtt.dd.qq.com/x.apk",
            "https://qq.com/",
            "intent://x#Intent;end",
        ).forEach { assertFalse(it, QqGuide.isAllowedNavigation(it)) }
    }

    @Test fun coachHasTwoSteps() {
        assertEquals(CoachState(1), QqGuide.coachStep(appPage, input))
        assertEquals(CoachState(2), QqGuide.coachStep("https://sj.qq.com/appdetail/com.sec.android.app.shealth", input))
        assertEquals(CoachState(2), QqGuide.coachStep("https://a.app.qq.com/o/simple.jsp?pkgname=com.other", input))
        assertEquals(CoachState(1), QqGuide.coachStep("https://evil.example/", input))
    }

    @Test fun downloadsFromTheCdnAreAcceptedAfterTheHttpsUpgrade() {
        assertEquals(cdnHttps, QqGuide.normalizeDownloadUrl(cdn))
        assertEquals(DownloadVerdict.Ok, QqGuide.isAcceptableDownload(cdn, null, "application/vnd.android.package-archive"))
        assertEquals(DownloadVerdict.Ok, QqGuide.isAcceptableDownload(cdnHttps, null, null))
        assertEquals(DownloadVerdict.Ok, QqGuide.isAcceptableDownload(cdn.replace("imtt.", "imtt2."), null, null))
        assertTrue(QqGuide.mayDownloadFrom(cdn))
    }

    @Test fun partialMirrorsAndOtherHostsAreRejected() {
        listOf(
            partial,
            partial.replace("http://", "https://"),
            "https://imtt.dd.qq.com/a/54477E5BA1DABFE2905A3BB3D54C06CD_offset_10.apk",
            "https://imtt.dd.qq.com/a/shealth.apk",
            "https://imtt.dd.qq.com/a/54477E5BA1DABFE2905A3BB3D54C06C.apk",
            "https://dd.qq.com/a/54477E5BA1DABFE2905A3BB3D54C06CD.apk",
            "https://evilimtt.dd.qq.com/a/54477E5BA1DABFE2905A3BB3D54C06CD.apk",
            "https://imtt.dd.qq.com.evil.tld/a/54477E5BA1DABFE2905A3BB3D54C06CD.apk",
            "https://u:p@imtt.dd.qq.com/a/54477E5BA1DABFE2905A3BB3D54C06CD.apk",
            "https://imtt.example/a/54477E5BA1DABFE2905A3BB3D54C06CD.apk",
        ).forEach {
            assertFalse(it, QqGuide.mayDownloadFrom(it))
            assertEquals(it, DownloadVerdict.NotHttps, QqGuide.isAcceptableDownload(it, null, null))
        }
    }

    @Test fun bundlesAreRejected() {
        assertEquals(DownloadVerdict.Bundle, QqGuide.isAcceptableDownload(cdn, "attachment; filename=\"app.xapk\"", null))
        assertEquals(DownloadVerdict.Bundle, QqGuide.isAcceptableDownload(cdn, null, "application/vnd.apkm"))
        assertEquals(DownloadVerdict.NotHttps, QqGuide.isAcceptableDownload(cdn.replace(".apk?", ".xapk?"), null, null))
    }

    @Test fun normalizeUpgradesOnlyQqCdnHosts() {
        assertEquals("http://example.com/a.apk", QqGuide.normalizeDownloadUrl("http://example.com/a.apk"))
        assertEquals("https://example.com/a.apk", QqGuide.normalizeDownloadUrl("https://example.com/a.apk"))
        assertEquals("http://qq.com.evil.tld/a.apk", QqGuide.normalizeDownloadUrl("http://qq.com.evil.tld/a.apk"))
        assertEquals("http://imtt.dd.qq.com:8080/a.apk", QqGuide.normalizeDownloadUrl("http://imtt.dd.qq.com:8080/a.apk"))
        assertEquals("not a url", QqGuide.normalizeDownloadUrl("not a url"))
    }

    @Test fun fsNameIsParsedForDisplayOnly() {
        assertEquals(QqFileName("com.sec.android.app.shealth", "7.00.6.012"), QqGuide.parseFsName(cdn))
        assertEquals(QqFileName("com.my_app", "1.0"), QqGuide.parseFsName("https://imtt.dd.qq.com/x.apk?fsname=com.my_app_1.0.apk"))
        assertNull(QqGuide.parseFsName("https://imtt.dd.qq.com/x.apk"))
        assertNull(QqGuide.parseFsName("https://imtt.dd.qq.com/x.apk?fsname=weird.apk"))
    }

    @Test fun chainAllowsTheDirectCdnLinkButNotHops() {
        val chain = RedirectChain(QqGuide)
        assertEquals(NavDecision.Allow, chain.onNavigation(appPage, isRedirect = false))
        assertEquals(NavDecision.Allow, chain.onNavigation(cdn, isRedirect = false))
        assertEquals(NavDecision.Block, chain.onNavigation(partial, isRedirect = false))
        assertEquals(NavDecision.Block, chain.onNavigation("https://other.example/a", isRedirect = true))
        assertTrue(chain.mayDownloadFrom(cdn))
        assertTrue(chain.mayDownloadFrom(cdnHttps))
        assertFalse(chain.mayDownloadFrom(partial))
        assertFalse(chain.mayDownloadFrom("https://downloads.apkmirror.com/a.apk"))
        assertFalse(chain.isHopHost(cdnHttps))
    }

    @Test fun chainWithoutQqOriginStillBlocksOtherSites() {
        val chain = RedirectChain(QqGuide)
        assertEquals(NavDecision.Block, chain.onNavigation("https://www.apkmirror.com/", isRedirect = false))
        assertEquals(NavDecision.Block, chain.onNavigation("http://imtt.dd.qq.com/a/shealth.apk", isRedirect = false))
    }

    @Test fun apkMirrorChainIgnoresQqLinks() {
        val chain = RedirectChain(ApkMirrorGuide)
        assertEquals(NavDecision.Block, chain.onNavigation(cdn, isRedirect = false))
        assertFalse(chain.mayDownloadFrom(cdnHttps))
    }

    @Test fun sourcesListPrimaryFirstAndYybIsOptIn() {
        assertEquals(listOf(InputSource.ApkMirror, InputSource.Yyb), input.sources())
        assertEquals(listOf(InputSource.ApkMirror), input.copy(onYyb = false).sources())
        assertEquals(listOf(InputSource.Yyb), input.copy(onApkMirror = false).sources())
        assertEquals(emptyList<InputSource>(), input.copy(onApkMirror = false, onYyb = false).sources())
        assertEquals(InputSource.Yyb, InputSource.fromArg("yyb"))
        assertEquals(InputSource.ApkMirror, InputSource.fromArg(null))
        assertEquals(InputSource.ApkMirror, InputSource.fromArg("bogus"))
    }

    @Test fun chinaModePutsYybFirstOnlyWhereTheAppIsOnYyb() {
        assertEquals(listOf(InputSource.Yyb, InputSource.ApkMirror), input.sources(chinaMode = true))
        assertEquals(listOf(InputSource.Yyb), input.copy(onApkMirror = false).sources(chinaMode = true))
        assertEquals(listOf(InputSource.ApkMirror, InputSource.Yyb), input.sources(chinaMode = false))
        assertEquals(listOf(InputSource.Yyb), input.copy(onApkMirror = false).sources(chinaMode = false))
        assertEquals(listOf(InputSource.ApkMirror), input.copy(onYyb = false).sources(chinaMode = true))
        assertEquals(emptyList<InputSource>(), input.copy(onApkMirror = false, onYyb = false).sources(chinaMode = true))
    }
}
