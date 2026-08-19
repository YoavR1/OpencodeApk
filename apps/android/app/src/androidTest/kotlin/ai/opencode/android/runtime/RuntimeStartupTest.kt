package ai.opencode.android.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starts and stops the real on-device runtime.
 *
 * This is the smoke test for the thing M7 exists to build, and it can only run on
 * a device: it launches an actual Node process from `nativeLibraryDir`, waits for
 * the server to answer, and shuts it down again.
 *
 * Skipped, not failed, when the APK was built without the runtime packaged - that
 * is a legitimate build (see the `reportRuntime` Gradle task), and a test that
 * failed for it would be reporting the build configuration rather than a defect.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeStartupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val packaged: Boolean
        get() = java.io.File(context.applicationInfo.nativeLibraryDir, "libnode.so").exists()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller() = LocalRuntimeController(
        context = context,
        scope = scope,
        versionName = "androidTest",
        stateDir = java.io.File(context.filesDir, "opencode-test"),
    )

    private fun get(url: String, credentials: Pair<String, String>?): Int {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            credentials?.let { (user, password) ->
                val token = android.util.Base64.encodeToString(
                    "$user:$password".toByteArray(),
                    android.util.Base64.NO_WRAP,
                )
                setRequestProperty("Authorization", "Basic $token")
            }
        }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun theRuntimeStartsServesAndStops() {
        if (!packaged) {
            println("[runtime] not packaged in this APK; skipping")
            return
        }

        val subject = controller()
        runBlocking {
            val handle = subject.awaitReady()

            // Q1: it is serving, on loopback, and it is the local one.
            assertTrue("expected a loopback address, got ${handle.url}", handle.url.startsWith("http://127.0.0.1:"))
            assertEquals(
                "the server should answer an authenticated health check",
                200,
                get("${handle.url}/global/health", (handle.username ?: "opencode") to handle.password!!),
            )

            // Auth is enforced even on loopback: other apps on the device can
            // reach it, which is the whole reason for the per-launch password.
            assertEquals(
                "an unauthenticated request must be refused",
                401,
                get("${handle.url}/global/health", null),
            )

            assertTrue(subject.state.value is RuntimeState.Ready)

            subject.stop()
            assertTrue(subject.state.value is RuntimeState.Stopped)

            // And it really is gone, rather than merely marked stopped.
            val afterStop = runCatching { get("${handle.url}/global/health", null) }
            assertTrue("the server should no longer answer", afterStop.isFailure)
        }
    }

    @Test
    fun startingTwiceReusesTheSameServer() {
        if (!packaged) {
            println("[runtime] not packaged in this APK; skipping")
            return
        }

        val subject = controller()
        runBlocking {
            val first = subject.awaitReady()
            val second = subject.awaitReady()
            assertEquals("a second call must not start a second server", first.url, second.url)
            subject.stop()
        }
    }
}
