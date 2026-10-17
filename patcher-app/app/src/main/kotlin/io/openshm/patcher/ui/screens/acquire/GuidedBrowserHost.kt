package io.openshm.patcher.ui.screens.acquire

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.openshm.patcher.R
import io.openshm.patcher.data.CoachState
import io.openshm.patcher.data.DownloadRequest
import io.openshm.patcher.data.DownloadVerdict
import io.openshm.patcher.data.InputApp
import io.openshm.patcher.data.InputSource
import io.openshm.patcher.data.VariantChip
import java.net.URI

private const val MIRROR_COACH_TOTAL = 3
private const val YYB_COACH_TOTAL = 2

/** What the notice over the page is about; kept apart from its text so it survives a language change. */
private sealed interface Notice {
    data class Blocked(val url: String) : Notice
    data object Bundle : Notice
    data object NotHttps : Notice
}

/**
 * The guided browser wired to a real WebView: the coach step follows the page, a blocked link or an
 * unsuitable download shows a notice, and an accepted download goes to [onDownload] (which also leaves
 * the browser).
 */
@Composable
fun GuidedBrowserHost(
    input: InputApp,
    source: InputSource,
    onDownload: (DownloadRequest) -> Unit,
    onUseFile: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val controller = remember { ApkMirrorWebController() }
    val guide = source.guide
    val isMirror = source == InputSource.ApkMirror
    var currentUrl by rememberSaveable(source) { mutableStateOf(guide.startUrl(input)) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    val coach = if (isMirror && !input.onApkMirror) CoachState(1) else guide.coachStep(currentUrl, input)
    val noMirror = isMirror && !input.onApkMirror
    val chips = input.variantChips.filter { it == VariantChip.ApkNotBundle || it == VariantChip.NoDpi }
        .map { stringResource(it.labelRes()) }
    val target = (listOf(input.versionName) + chips + listOfNotNull(input.abis.takeIf { it.isNotEmpty() }?.joinToString(" + ")))
        .joinToString(" · ")

    GuidedBrowserScreen(
        state = GuidedBrowserUiState(
            host = hostOf(currentUrl) ?: hostOf(guide.startUrl(input)).orEmpty(),
            appLabel = input.label,
            coachStep = coach.step,
            coachTotal = if (isMirror) MIRROR_COACH_TOTAL else YYB_COACH_TOTAL,
            coachTitle = stringResource(
                when {
                    noMirror -> R.string.browser_coach_no_mirror_title
                    isMirror -> coachTitle(coach.step)
                    else -> yybCoachTitle(coach.step)
                },
            ),
            coachBody = when {
                noMirror -> stringResource(R.string.browser_coach_no_mirror_body, input.versionName)
                !isMirror -> stringResource(yybCoachBody(coach.step), input.label)
                coach.wrongVersion -> stringResource(R.string.browser_coach_wrong_version_body, input.versionName)
                else -> stringResource(coachBody(coach.step), input.versionName)
            },
            target = target,
            notice = notice?.toUi(),
        ),
        onEvent = { event ->
            when (event) {
                GuidedBrowserEvent.Close -> onClose()
                GuidedBrowserEvent.UseFile -> onUseFile()
                GuidedBrowserEvent.Reload -> controller.reload()
                GuidedBrowserEvent.BackToRightPage -> controller.load(guide.startUrl(input))
                GuidedBrowserEvent.OpenInBrowser -> openExternally(context, currentUrl)
                GuidedBrowserEvent.OpenNoticeExternally -> {
                    (notice as? Notice.Blocked)?.let { openExternally(context, it.url) }
                    notice = null
                }
                GuidedBrowserEvent.DismissNotice -> notice = null
            }
        },
        page = {
            ApkMirrorWebView(
                guide = guide,
                initialUrl = currentUrl,
                controller = controller,
                onPage = { url ->
                    if (guide.isAllowedNavigation(url)) currentUrl = url
                },
                onBlocked = { notice = Notice.Blocked(it) },
                onDownload = { request ->
                    // The request carries the normalized URL (e.g. https for an http CDN link).
                    when (guide.isAcceptableDownload(request.url, request.contentDisposition, request.mimeType)) {
                        DownloadVerdict.Ok -> onDownload(request.copy(url = guide.normalizeDownloadUrl(request.url)))
                        DownloadVerdict.Bundle -> notice = Notice.Bundle
                        DownloadVerdict.NotHttps -> notice = Notice.NotHttps
                    }
                },
            )
        },
    )
}

@Composable
private fun Notice.toUi(): BrowserNotice = when (this) {
    is Notice.Blocked -> BrowserNotice(
        stringResource(R.string.browser_notice_blocked, hostOf(url) ?: "?"),
        canOpenExternally = url.startsWith("https://") || url.startsWith("http://"),
    )
    Notice.Bundle -> BrowserNotice(stringResource(R.string.browser_notice_bundle))
    Notice.NotHttps -> BrowserNotice(stringResource(R.string.browser_notice_not_https))
}

private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

/** Hands an http(s) page to the system browser; anything else is ignored. */
private fun openExternally(context: Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No browser installed: nothing else to offer.
    }
}

private fun coachTitle(step: Int) = when (step) {
    1 -> R.string.browser_coach_1_title
    2 -> R.string.browser_coach_2_title
    else -> R.string.browser_coach_3_title
}

private fun coachBody(step: Int) = when (step) {
    1 -> R.string.browser_coach_1_body_version
    2 -> R.string.browser_coach_2_body_version
    else -> R.string.browser_coach_3_body
}

private fun yybCoachTitle(step: Int) = if (step == 1) R.string.browser_yyb_coach_1_title else R.string.browser_yyb_coach_2_title

private fun yybCoachBody(step: Int) = if (step == 1) R.string.browser_yyb_coach_1_body else R.string.browser_yyb_coach_2_body
