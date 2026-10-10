package io.openshm.patcher.ui.screens.acquire

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.openshm.patcher.data.ApkMirrorGuide
import io.openshm.patcher.data.DownloadRequest

/** Lets the screen drive the WebView (reload, load a URL) without owning it. */
class ApkMirrorWebController {
    internal var view: WebView? = null

    fun reload() {
        view?.reload()
    }

    fun load(url: String) {
        view?.loadUrl(url)
    }
}

/**
 * The APKMirror page. Only https pages on apkmirror.com load; a top-level navigation anywhere else is
 * reported through [onBlocked] and not loaded. A download is not handled by the WebView: it is passed to
 * [onDownload] with the cookies the WebView holds for that URL. The WebView is destroyed with the composable.
 */
@Composable
fun ApkMirrorWebView(
    initialUrl: String,
    controller: ApkMirrorWebController,
    onPage: (String) -> Unit,
    onBlocked: (String) -> Unit,
    onDownload: (DownloadRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page by rememberUpdatedState(onPage)
    val blocked by rememberUpdatedState(onBlocked)
    val download by rememberUpdatedState(onDownload)
    var canGoBack by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> controller.view?.onPause()
                Lifecycle.Event.ON_RESUME -> controller.view?.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = canGoBack) { controller.view?.goBack() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                configure(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val url = request.url.toString()
                        if (ApkMirrorGuide.isAllowedNavigation(url)) return false
                        blocked(url)
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = page(url)

                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        canGoBack = view.canGoBack()
                        page(url)
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = handler.cancel()
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
                        callback.invoke(origin, false, false)
                }
                setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    val cookie = CookieManager.getInstance().getCookie(url)
                    download(DownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength, cookie))
                }
                controller.view = this
                loadUrl(initialUrl)
            }
        },
        onRelease = { view ->
            if (controller.view === view) controller.view = null
            view.stopLoading()
            view.setDownloadListener(null)
            view.destroy()
        },
    )
}

// The APKMirror pages cannot render without JavaScript; the page is limited to apkmirror.com by the
// navigation check, and nothing is exposed to it (no JavaScript interface, no file or content access).
@SuppressLint("SetJavaScriptEnabled")
private fun configure(view: WebView) {
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = true
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
}
