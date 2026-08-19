package ai.opencode.android

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ai.opencode.android.bridge.BridgeHost
import ai.opencode.android.platform.Notifications
import ai.opencode.android.util.SafeLog
import ai.opencode.android.web.BridgePort
import ai.opencode.android.web.WebOrigin
import ai.opencode.android.web.WebViewHost

/**
 * The single Activity hosting the OpenCode UI.
 *
 * It is treated as disposable throughout: Android may destroy and recreate it at
 * any time, so nothing durable lives here (.claude/rules/android.md N7). Durable
 * state is in preferences and, from M7, on the server side.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridgePort: BridgePort
    private lateinit var bridgeHost: BridgeHost
    private var backCallback: OnBackPressedCallback? = null

    private val back = BackCoordinator(
        emit = { token -> bridgeHost.emitBack(token) },
        exit = { exitFromBack() },
        // Posted to the WebView so the timeout runs on the main thread, where
        // both the dispatcher and the bridge reply already are.
        schedule = { delayMs, action -> webView.postDelayed(action, delayMs) },
    )

    /**
     * Registered unconditionally at construction, because
     * `registerForActivityResult` must run before the Activity is STARTED. The
     * result only matters for whether the next notification can be posted.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            SafeLog.d("POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)

        bridgePort = BridgePort(webView) { message -> bridgeHost.handle(message) }
        bridgeHost = BridgeHost(
            activity = this,
            scope = lifecycleScope,
            send = bridgePort::send,
            onBackHandled = back::handled,
        )

        WebViewHost.configure(
            webView = webView,
            context = applicationContext,
            onRendererGone = { if (!isFinishing && !isDestroyed) recreate() },
            onPageStarted = {
                // The channel belongs to the document being replaced.
                bridgePort.invalidate()
            },
            onPageFinished = {
                // The port is delivered to a live document, so it can only be
                // connected once the page exists.
                bridgePort.connect()
                consumeNotificationTag(intent)
            },
        )

        Notifications(applicationContext).ensureChannel()
        requestNotificationPermissionOnce()

        applyInsets()
        registerBackHandling()

        if (savedInstanceState == null) {
            SafeLog.d("loading ${WebOrigin.INDEX_URL}")
            webView.loadUrl(WebOrigin.INDEX_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    /**
     * Content insets itself because the window is edge-to-edge.
     *
     * The IME inset is applied as bottom padding and its height is also reported
     * to the renderer: the shared UI needs to know how much room the keyboard has
     * taken so a focused input is not left underneath it. Getting this wrong is
     * the single most obvious way a WebView app feels broken on a phone.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboard = (ime.bottom - bars.bottom).coerceAtLeast(0)

            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom + keyboard,
            )

            if (bridgePort.connected) {
                val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
                bridgeHost.emitKeyboard((keyboard / density).toInt())
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Back goes through OnBackPressedDispatcher, the only form that supports
     * predictive back on API 33+, which the manifest opts into.
     *
     * The press is offered to the renderer, which owns the dialog, drawer and
     * route state that back should unwind; see [BackCoordinator] for why the
     * Activity cannot decide this itself and how the app still exits if the
     * renderer does not answer.
     */
    private fun registerBackHandling() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                back.press(rendererReachable = bridgePort.connected)
            }
        }
        backCallback = callback
        onBackPressedDispatcher.addCallback(this, callback)
    }

    /**
     * Leaves the app by handing the press back to the default handler.
     *
     * The callback is disabled for exactly that one dispatch and re-enabled
     * afterwards: if anything other than finishing happens, back must keep
     * working rather than becoming permanently dead.
     */
    private fun exitFromBack() {
        val callback = backCallback ?: return
        callback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
        callback.isEnabled = true
    }

    /**
     * Asks for POST_NOTIFICATIONS once, and only on the versions that have it.
     *
     * Not a blocking gate: a refused permission degrades notifications, it does
     * not stop the app, and `Notifications.post` reports the refusal honestly
     * rather than pretending it posted.
     */
    private fun requestNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (Notifications(applicationContext).permitted()) return
        runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationTag(intent)
    }

    /** Relays a tapped notification's tag to the renderer, once. */
    private fun consumeNotificationTag(intent: Intent?) {
        val tag = intent?.getStringExtra(Notifications.EXTRA_TAG) ?: return
        intent.removeExtra(Notifications.EXTRA_TAG)
        if (bridgePort.connected) bridgeHost.emitNotificationClicked(tag)
    }

    override fun onResume() {
        super.onResume()
        if (bridgePort.connected) bridgeHost.emitLifecycle("resumed")
    }

    override fun onPause() {
        if (bridgePort.connected) bridgeHost.emitLifecycle("paused")
        super.onPause()
    }

    override fun onStop() {
        if (bridgePort.connected) bridgeHost.emitLifecycle("stopped")
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        bridgePort.disconnect()
        // The WebView holds a reference to the Activity context; detaching before
        // destruction avoids leaking the Activity on rotation.
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    /** Kept for the instrumented tests, which assert the WebView is reachable. */
    internal fun webViewForTest(): View = webView
}
