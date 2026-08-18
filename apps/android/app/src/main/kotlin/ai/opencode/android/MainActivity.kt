package ai.opencode.android

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ai.opencode.android.util.SafeLog
import ai.opencode.android.web.WebOrigin
import ai.opencode.android.web.WebViewHost

/**
 * The single Activity hosting the OpenCode UI.
 *
 * M2 scope: prove the shell builds, launches and renders a bundled page. The
 * shared OpenCode UI replaces the placeholder asset in M3; no server of any kind
 * is contacted here.
 *
 * The Activity is deliberately treated as disposable — Android may destroy and
 * recreate it at any time (.claude/rules/android.md N7). Nothing durable is kept
 * in it. That property is what M9 will lean on.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.web_view)
        WebViewHost.configure(
            webView = webView,
            context = applicationContext,
            onRendererGone = {
                // The renderer died. The Activity rebuilds itself with a fresh WebView.
                if (!isFinishing && !isDestroyed) recreate()
            },
            onRestart = {
                // Platform.restart() from the shared UI. Must hop to the main
                // thread: the bridge call arrives on a WebView JS thread.
                runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
            },
        )

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
     * The window is edge-to-edge, so content must inset itself. Getting this wrong
     * is what puts a text input underneath the soft keyboard, so it is wired from
     * the start rather than retrofitted.
     *
     * `ime()` is included here; the finer keyboard behaviour the prompt input
     * needs is M4 work.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
                    or WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * Back navigation goes through [androidx.activity.OnBackPressedDispatcher] —
     * the only form that supports predictive back on API 33+, which the manifest
     * opts into.
     *
     * When the WebView has no history left the callback disables itself and
     * re-dispatches, so the default behaviour (finish the Activity) takes over
     * rather than trapping the user.
     */
    private fun registerBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                // No web history left: step out of the way and let the default
                // handler finish the Activity, rather than trapping the user.
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        // The WebView holds a reference to the Activity context; detaching it
        // before destruction avoids leaking the Activity on rotation.
        webView.destroy()
        super.onDestroy()
    }
}
