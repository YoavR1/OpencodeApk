package ai.opencode.android

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opencode.android.web.WebOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M2's core claim is "the shell cold-launches and renders a bundled page".
 * These tests assert exactly that, plus the WebView security settings that the
 * whole design depends on.
 *
 * Not yet run in CI — no emulator job exists until it is justified by more than
 * one test. See docs/TEST_MATRIX.md.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityLaunchTest {

    @Test
    fun activityLaunchesWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.webkit.WebView>(R.id.web_view))
            }
        }
    }

    @Test
    fun applicationIdMatchesTheDocumentedValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("ai.opencode.android", context.packageName)
    }

    @Test
    fun webViewSecuritySettingsAreLockedDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val settings = activity.findViewById<android.webkit.WebView>(R.id.web_view).settings
                assertTrue("JavaScript must be enabled", settings.javaScriptEnabled)
                assertFalse("file access must stay off", settings.allowFileAccess)
                @Suppress("DEPRECATION")
                assertFalse(settings.allowFileAccessFromFileURLs)
                @Suppress("DEPRECATION")
                assertFalse(settings.allowUniversalAccessFromFileURLs)
            }
        }
    }

    /**
     * The load must come back from the app's https asset origin, not file://.
     * That is the property ADR-0008 exists to guarantee, and the one that would
     * silently break fetch/CORS for the real UI in M3.
     */
    @Test
    fun placeholderLoadsFromTheHttpsAssetOrigin() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val loaded = CountDownLatch(1)
            val origin = arrayOfNulls<String>(1)

            scenario.onActivity { activity ->
                val webView = activity.findViewById<android.webkit.WebView>(R.id.web_view)
                webView.postDelayed(object : Runnable {
                    override fun run() {
                        val url = webView.url
                        if (url != null && webView.progress == 100) {
                            origin[0] = url
                            loaded.countDown()
                        } else {
                            webView.postDelayed(this, POLL_MS)
                        }
                    }
                }, POLL_MS)
            }

            assertTrue("page did not finish loading", loaded.await(20, TimeUnit.SECONDS))
            assertTrue(
                "expected the app asset origin, got ${origin[0]}",
                WebOrigin.isAppOrigin(origin[0]),
            )
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
