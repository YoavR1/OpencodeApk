package ai.opencode.android.web

import android.content.Context
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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

    fun configure(
        webView: WebView,
        context: Context,
        onRendererGone: () -> Unit,
        onRestart: () -> Unit,
    ) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WebOrigin.DOMAIN)
            .addPathHandler(WebOrigin.ASSET_PATH, WebViewAssetLoader.AssetsPathHandler(context))
            .build()

        webView.webViewClient = AssetClient(loader, onRendererGone)

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

        installHostBridge(webView, context, onRestart)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }

    /**
     * Exposes `window.__OPENCODE_ANDROID__` to the renderer.
     *
     * The adapter script must run before the app bundle. `addDocumentStartJavaScript`
     * guarantees that; where the WebView is too old to support it, injecting on
     * page start is close enough, because the renderer reads the host lazily and
     * only at render time. Either way the renderer treats the object as optional,
     * so a failure here degrades to browser behaviour rather than a broken app.
     */
    private fun installHostBridge(webView: WebView, context: Context, onRestart: () -> Unit) {
        webView.addJavascriptInterface(
            AndroidHostBridge(context, onRestart),
            AndroidHostBridge.INTERFACE_NAME,
        )

        val script = AndroidHostBridge.bootstrapScript()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(WebOrigin.ORIGIN))
            SafeLog.d("host bridge installed via document-start script")
        } else {
            SafeLog.w("DOCUMENT_START_SCRIPT unsupported; falling back to onPageStarted")
            pendingBootstrap = script
        }
    }

    /** Set only when the document-start API is unavailable. */
    private var pendingBootstrap: String? = null

    // Lint's MissingOnRenderProcessGone check does not resolve the override below
    // on this Kotlin declaration and reports it as missing. The Kotlin compiler
    // accepts `override`, which only succeeds if the method genuinely binds to
    // WebViewClient.onRenderProcessGone, so the check is wrong here rather than
    // being satisfied by suppression. Re-test on future AGP versions and remove
    // this if it starts resolving.
    @Suppress("MissingOnRenderProcessGone")
    private class AssetClient(
        private val loader: WebViewAssetLoader,
        private val onRendererGone: () -> Unit,
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            pendingBootstrap?.let { view.evaluateJavascript(it, null) }
        }

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

        /**
         * The WebView renderer runs in its own process and can be killed
         * independently — by a crash, or by the system reclaiming memory. Without
         * this override the framework kills the whole app when that happens.
         *
         * Returning `true` claims the event so the app survives. The WebView
         * itself is unusable afterwards, so it is detached and the host is asked
         * to rebuild it.
         *
         * Crash-loop backoff is deliberately not implemented here; that belongs
         * with the rest of the resilience work in M9.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            SafeLog.e("WebView render process gone (didCrash=${detail.didCrash()})")
            (view.parent as? ViewGroup)?.removeView(view)
            onRendererGone()
            return true
        }
    }
}
