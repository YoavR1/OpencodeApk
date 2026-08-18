package ai.opencode.android.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import ai.opencode.android.BuildConfig
import ai.opencode.android.util.SafeLog

/**
 * Configures a [WebView] to host the OpenCode UI from APK assets.
 *
 * Security posture (see .claude/rules/android.md N6):
 *  - assets are served over `https://` by [WebViewAssetLoader], never `file://`,
 *    so fetch, CORS and web storage behave normally;
 *  - file-URL access is disabled in both forms;
 *  - navigation away from the app origin is refused rather than followed.
 *
 * No JavaScript bridge is installed yet. That arrives in M3/M4 as a typed
 * `WebMessagePort` channel (docs/ARCHITECTURE.md 2.4).
 */
object WebViewHost {

    fun configure(webView: WebView, context: Context) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WebOrigin.DOMAIN)
            .addPathHandler(WebOrigin.ASSET_PATH, WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        webView.webViewClient = AssetClient(loader)

        webView.settings.apply {
            javaScriptEnabled = true

            // The UI relies on web storage; the server holds the real state.
            domStorageEnabled = true

            // Never widen the origin model. A file:// document must not be able to
            // read other files or reach the app origin.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false

            // The UI is built for the viewport it is given; no desktop emulation.
            useWideViewPort = false
            loadWithOverviewMode = false

            // Mixed content is meaningless here (assets are same-origin https) and
            // must stay disallowed once the loopback server is added in M7.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    private class AssetClient(private val loader: WebViewAssetLoader) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url?.toString()
            if (WebOrigin.isAppOrigin(url)) return false // handle in the WebView

            // External navigation is not part of M2. Opening it in the browser is
            // the Platform.openExternal contract and lands with the bridge in M4.
            SafeLog.w("blocked off-origin navigation")
            return true
        }
    }
}
