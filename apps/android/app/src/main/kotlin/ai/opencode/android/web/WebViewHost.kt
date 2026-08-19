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
 * No JavaScript object is injected into the page. Native capabilities reach the
 * renderer through a `WebMessagePort` opened by [BridgePort] once the document
 * has loaded, which is why this class only reports `onPageFinished` rather than
 * installing anything itself.
 */
object WebViewHost {

    fun configure(
        webView: WebView,
        context: Context,
        onRendererGone: () -> Unit,
        onPageStarted: () -> Unit,
        onPageFinished: () -> Unit,
    ) {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WebOrigin.DOMAIN)
            .addPathHandler(WebOrigin.ASSET_PATH, WebAssetsHandler(context))
            .build()

        webView.webViewClient = AssetClient(loader, onRendererGone, onPageStarted, onPageFinished)

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



    /**
     * Serves the app from the origin root while keeping its files tidy inside the
     * APK.
     *
     * The shared UI must be served from `/` (see WebOrigin.ASSET_PATH), but its
     * files live under `assets/web/` so they do not collide with anything else
     * the APK may carry later. `AssetsPathHandler` resolves relative to the
     * assets root, so this handler re-prefixes each request.
     */
    private class WebAssetsHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
        private val delegate = WebViewAssetLoader.AssetsPathHandler(context)

        /**
         * Computed once, from the packaged document, on first use.
         *
         * Reading an asset is cheap but not free, and this runs on the WebView's
         * resource thread for every request the loader handles.
         */
        private val policy: String by lazy {
            val html = runCatching {
                context.assets.open(ASSET_SUBDIR + DOCUMENT).use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrElse {
                // A policy without the document's own hashes would block the app's
                // theme preload. Failing closed here would be failing to start, so
                // the loss is that one inline script; everything else still applies.
                SafeLog.w("could not read $DOCUMENT to hash its inline scripts", it)
                ""
            }
            ContentSecurityPolicy.forDocument(ContentSecurityPolicy.inlineScriptHashes(html))
        }

        override fun handle(path: String): WebResourceResponse? {
            val response = delegate.handle(ASSET_SUBDIR + path.trimStart('/')) ?: return null

            // Only the document carries the policy. Attaching it to every asset
            // would be noise: a CSP applies to the context a document creates,
            // and a stylesheet does not create one.
            if (isDocument(path)) {
                response.responseHeaders = (response.responseHeaders ?: emptyMap()) +
                    mapOf(
                        "Content-Security-Policy" to policy,
                        // The document is same-origin only; nothing should be
                        // sniffing a type or framing this.
                        "X-Content-Type-Options" to "nosniff",
                        "Referrer-Policy" to "no-referrer",
                    )
            }
            return response
        }

        private fun isDocument(path: String): Boolean {
            val clean = path.substringBefore('?').trimStart('/')
            return clean.isEmpty() || clean.endsWith(".html", ignoreCase = true)
        }

        private companion object {
            const val ASSET_SUBDIR = "web/"
            const val DOCUMENT = "index.html"
        }
    }

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
        private val onPageStarted: () -> Unit,
        private val onPageFinished: () -> Unit,
    ) : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // A new document is replacing the old one, so any bridge channel
            // bound to the old one is dead and must not be reused.
            if (WebOrigin.isAppOrigin(url)) onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // The bridge port is delivered to a live document, so the channel can
            // only be opened once the page has actually loaded.
            if (WebOrigin.isAppOrigin(url)) onPageFinished()
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
