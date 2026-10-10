package io.openshm.patcher

import io.openshm.patcher.data.ApkMirrorGuide
import io.openshm.patcher.data.CoachState
import io.openshm.patcher.data.Device
import io.openshm.patcher.data.DownloadVerdict
import io.openshm.patcher.data.InputApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkMirrorGuideTest {
    private val input = InputApp(
        "shm-watch", "Health Monitor", "1.4.6.001", Device.Watch, "0".repeat(64), "com.example.shm",
        "samsung-electronics-co-ltd/samsung-health-monitor-2", emptyList(),
    )
    private val base = "https://www.apkmirror.com/apk/samsung-electronics-co-ltd/samsung-health-monitor-2/"
    private val release = base + "samsung-health-monitor-2-1-4-6-001-release/"

    @Test fun startUrlIsTheAppPage() {
        assertEquals(base, ApkMirrorGuide.startUrl(input))
    }

    @Test fun allowListAcceptsOnlyHttpsApkMirror() {
        assertTrue(ApkMirrorGuide.isAllowedNavigation(base))
        assertTrue(ApkMirrorGuide.isAllowedNavigation("https://apkmirror.com/"))
        assertTrue(ApkMirrorGuide.isAllowedNavigation("https://WWW.APKMirror.com:443/x?y=1"))
        assertTrue(ApkMirrorGuide.isAllowedNavigation("https://downloads.apkmirror.com/a"))
    }

    @Test fun allowListRejectsLookalikesAndOtherSchemes() {
        listOf(
            "http://www.apkmirror.com/",
            "https://apkmirror.com.evil.example/",
            "https://evilapkmirror.com/",
            "https://apkmirror.com@evil.example/",
            "https://user:pw@www.apkmirror.com/",
            "https://evil.example/https://apkmirror.com/",
            "https://www.apkmirror.com:8443/",
            "https://www.apkmirror.com\\@evil.example/",
            "intent://apkmirror.com/#Intent;end",
            "javascript:alert(1)",
            "data:text/html,hi",
            "blob:https://www.apkmirror.com/abc",
            "file:///sdcard/a.apk",
            "",
            "not a url",
        ).forEach { assertFalse(it, ApkMirrorGuide.isAllowedNavigation(it)) }
    }

    @Test fun stepsFollowThePage() {
        assertEquals(CoachState(1), ApkMirrorGuide.coachStep(base, input))
        assertEquals(CoachState(1), ApkMirrorGuide.coachStep("https://www.apkmirror.com/", input))
        assertEquals(CoachState(2), ApkMirrorGuide.coachStep(release, input))
        assertEquals(CoachState(2), ApkMirrorGuide.coachStep(release.trimEnd('/') + "?x=1", input))
        assertEquals(
            CoachState(3),
            ApkMirrorGuide.coachStep(release + "samsung-health-monitor-2-1-4-6-001-android-apk-download/", input),
        )
        assertEquals(
            CoachState(3),
            ApkMirrorGuide.coachStep(release + "samsung-health-monitor-2-1-4-6-001-android-apk-download/download/?key=abc", input),
        )
    }

    @Test fun anotherVersionIsFlaggedOnStepOne() {
        assertEquals(
            CoachState(1, wrongVersion = true),
            ApkMirrorGuide.coachStep(base + "samsung-health-monitor-2-1-5-1-022-release/", input),
        )
        assertEquals(
            CoachState(1, wrongVersion = true),
            ApkMirrorGuide.coachStep(base + "samsung-health-monitor-2-1-4-6-0010-release/", input),
        )
        assertEquals(
            CoachState(1, wrongVersion = true),
            ApkMirrorGuide.coachStep(base + "samsung-health-monitor-2-1-5-1-022-release/x-download/", input),
        )
    }

    @Test fun otherSitesAndOtherAppsAreStepOne() {
        assertEquals(CoachState(1), ApkMirrorGuide.coachStep("https://evil.example/" + release.substringAfter(".com/"), input))
        assertEquals(CoachState(1), ApkMirrorGuide.coachStep("https://www.apkmirror.com/apk/other/app/app-1-4-6-001-release/", input))
        assertEquals(CoachState(1), ApkMirrorGuide.coachStep("garbage", input))
    }

    @Test fun bundlesAndInsecureDownloadsAreRefused() {
        val url = "https://downloads.apkmirror.com/wp-content/uploads/file/app.apk?token=1"
        assertEquals(DownloadVerdict.Ok, ApkMirrorGuide.isAcceptableDownload(url, null, "application/vnd.android.package-archive"))
        assertEquals(DownloadVerdict.Ok, ApkMirrorGuide.isAcceptableDownload(url, "attachment; filename=\"a.apk\"", null))
        assertEquals(DownloadVerdict.Ok, ApkMirrorGuide.isAcceptableDownload("https://x.example/download/", null, null))
        for (ext in listOf("apkm", "xapk", "apks", "zip", "APKM")) {
            assertEquals(
                ext, DownloadVerdict.Bundle,
                ApkMirrorGuide.isAcceptableDownload(url, "attachment; filename=\"a.$ext\"", null),
            )
            assertEquals(ext, DownloadVerdict.Bundle, ApkMirrorGuide.isAcceptableDownload("https://x.example/a.$ext?x=1", null, null))
        }
        assertEquals(
            DownloadVerdict.Bundle,
            ApkMirrorGuide.isAcceptableDownload(url, "attachment; filename*=UTF-8''a%20b.apkm", null),
        )
        assertEquals(DownloadVerdict.NotHttps, ApkMirrorGuide.isAcceptableDownload("http://x.example/a.apk", null, null))
        assertEquals(DownloadVerdict.NotHttps, ApkMirrorGuide.isAcceptableDownload("blob:https://x.example/1", null, null))
        assertEquals(DownloadVerdict.NotHttps, ApkMirrorGuide.isAcceptableDownload("data:application/zip;base64,AA", null, null))
    }
}
