package ai.opencode.android.web

import android.os.Looper
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

        // Idempotent for a given document. onPageFinished fires more than once
        // on a cold start, and reconnecting would close the channel the page is
        // already using: the renderer has stopped listening for handshakes by
        // then, so it keeps posting into a port whose host end has just been
        // closed and simply never hears back. That is precisely what M5 shipped,
        // and it only appeared on a device - a reload happened to fire
        // onPageFinished once and looked perfectly healthy.
        //
        // A genuinely new document calls invalidate() first, via onPageStarted.
        if (connected) return

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

    /**
     * Sends a reply or event to the page.
     *
     * Safe to call from any thread. WebView APIs - `WebMessagePort` included -
     * must run on the thread that created the WebView, and bridge handlers
     * deliberately do disk work on `Dispatchers.IO` and reply from there. Doing
     * the hop here means no handler has to remember, which is the kind of thing
     * that is remembered right up until the one place it is not.
     *
     * Getting this wrong is silent: the post throws, the renderer never receives
     * its answer, and the promise it is waiting on simply times out fifteen
     * seconds later with nothing to say why. That is exactly what M5 shipped,
     * and only a device showed it.
     */
    fun send(payload: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) post(payload) else webView.post { post(payload) }
    }

    private fun post(payload: String) {
        val port = hostPort ?: return
        runCatching { port.postMessage(WebMessageCompat(payload)) }
            .onFailure { SafeLog.w("could not post a bridge message", it) }
    }

    /**
     * Drops the channel because the document it belonged to is going away.
     *
     * Called when a new page starts loading: the page's end of the channel dies
     * with its document, so the next [connect] must build a fresh one.
     */
    fun invalidate() = disconnect()

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
