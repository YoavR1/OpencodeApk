package ai.opencode.android.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.webkit.JavascriptInterface
import ai.opencode.android.BuildConfig
import ai.opencode.android.util.SafeLog

/**
 * The `window.__OPENCODE_ANDROID__` object the renderer talks to.
 *
 * M3 keeps this as small as it can be: the three members upstream's `Platform`
 * type requires, and nothing else. The typed `WebMessagePort` channel described
 * in docs/ARCHITECTURE.md 2.4 replaces this in M4, when storage, drafts and
 * pickers arrive and the surface stops being trivially small.
 *
 * `@JavascriptInterface` is used rather than `WebMessagePort` only because the
 * surface is three fire-and-forget methods with no return values. Every method
 * validates its own input and none of them can reach a capability the page could
 * not already reach.
 */
class AndroidHostBridge(
    private val context: Context,
    private val onRestart: () -> Unit,
) {

    /** Read by the renderer to populate `Platform.version`. */
    @JavascriptInterface
    fun versionName(): String = BuildConfig.VERSION_NAME

    /**
     * Hands a URL to the system browser.
     *
     * The scheme is re-checked here even though the renderer already checks it:
     * this method is reachable from any script in the WebView, so it must not
     * rely on a caller it does not control. Without this, page content could
     * launch arbitrary Intents.
     */
    @JavascriptInterface
    fun openExternal(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) {
            SafeLog.w("refused openExternal for scheme=${uri.scheme}")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Never let this resolve back into our own app.
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            SafeLog.w("no activity to handle openExternal", error)
        }
    }

    /** Recreates the hosting Activity. */
    @JavascriptInterface
    fun restart() {
        SafeLog.d("restart requested by renderer")
        onRestart()
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")

        /** The name the bridge is registered under inside the WebView. */
        const val INTERFACE_NAME: String = "__OPENCODE_ANDROID_HOST__"

        /**
         * Script that adapts the injected Java object into the shape the
         * renderer expects.
         *
         * It runs before the bundle because it is injected on page start. The
         * renderer treats `window.__OPENCODE_ANDROID__` as optional, so if this
         * ever fails to run the app still boots — it just falls back to the
         * browser behaviour.
         */
        fun bootstrapScript(): String = """
            (function () {
              var host = window.$INTERFACE_NAME;
              if (!host) return;
              window.__OPENCODE_ANDROID__ = {
                versionName: host.versionName(),
                openExternal: function (url) { host.openExternal(String(url)); },
                restart: function () { host.restart(); }
              };
            })();
        """.trimIndent()
    }
}
