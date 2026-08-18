package ai.opencode.android.web

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.net.toUri
import ai.opencode.android.util.SafeLog

/**
 * Hands one end of a `WebMessagePort` pair to the page and keeps the other.
 *
 * Chosen over `addJavascriptInterface`, which injects a reflective object into
 * every frame. A port is a plain message channel: the page can only send strings
 * down it, and the host decides what any of them mean.
 *
 * The port is posted to the page's origin only, so a frame from anywhere else
 * cannot receive it.
 */
// Lint's RequiresFeature check is not interprocedural: it cannot see that every
// call below is reachable only past the REQUIRED_FEATURES gate in connect(), and
// flags each one. The gate is the check it asks for, applied once instead of
// five times.
@Suppress("RequiresFeature")
class BridgePort(
    private val webView: WebView,
    private val onMessage: (String) -> Unit,
) {
    private var hostPort: WebMessagePortCompat? = null

    val connected: Boolean get() = hostPort != null

    /**
     * Establishes the channel. Must be called after the page has loaded, since
     * the port is delivered to the live document.
     *
     * Every port feature this class will ever use is checked here, not at each
     * call site: `send` and `disconnect` run only on a port that `connect`
     * produced, so gating once means they cannot reach an unsupported API. In
     * practice these features shipped together, but a WebView that supports
     * only some of them gets no bridge rather than a half-working one.
     */
    fun connect() {
        if (REQUIRED_FEATURES.any { !WebViewFeature.isFeatureSupported(it) }) {
            SafeLog.w("WebMessagePort unsupported on this WebView; native capabilities unavailable")
            return
        }

        disconnect()

        val (host, page) = runCatching { WebViewCompat.createWebMessageChannel(webView) }
            .getOrNull()
            ?.takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
            ?: run {
                SafeLog.w("could not create a web message channel")
                return
            }

        host.setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
            override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                val data = message?.data ?: return
                onMessage(data)
            }
        })
        hostPort = host

        // Delivering the page's end. Scoped to the app origin so no other origin
        // can pick it up.
        runCatching {
            WebViewCompat.postWebMessage(
                webView,
                WebMessageCompat(HANDSHAKE, arrayOf(page)),
                WebOrigin.ORIGIN.toUri(),
            )
        }.onFailure {
            SafeLog.w("could not deliver the bridge port to the page", it)
            disconnect()
        }
    }

    fun send(payload: String) {
        val port = hostPort ?: return
        runCatching { port.postMessage(WebMessageCompat(payload)) }
            .onFailure { SafeLog.w("could not post a bridge message", it) }
    }

    fun disconnect() {
        hostPort?.let { runCatching { it.close() } }
        hostPort = null
    }

    private companion object {
        /** The renderer listens for this to know which message carries the port. */
        const val HANDSHAKE = "opencode:bridge-port"

        private val REQUIRED_FEATURES = listOf(
            WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL,
            WebViewFeature.POST_WEB_MESSAGE,
            WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK,
            WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE,
            WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
        )
    }
}
