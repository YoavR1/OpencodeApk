package ai.opencode.android.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The controller's job is to ensure exactly one server is ever started.
 *
 * The renderer asks for an address as soon as it loads, and again after any
 * reload. A second start would leave an orphaned process holding a port and a
 * second copy of the database. These pin that, and pin that one failure does not
 * poison the controller for the rest of the process.
 *
 * The runtime itself is faked: starting a real one needs a device, and that is
 * covered by RuntimeStartupTest in androidTest.
 */
@RunWith(RobolectricTestRunner::class)
class LocalRuntimeControllerTest {

    /** Counts starts, and can be held open to create a real race. */
    private class FakeRuntime(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failWith: Throwable? = null,
    ) : OpencodeRuntime {
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)
        private val mutable = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
        override val state: StateFlow<RuntimeState> = mutable.asStateFlow()

        override suspend fun start(config: RuntimeConfig): RuntimeHandle {
            starts.incrementAndGet()
            gate?.await()
            failWith?.let { throw it }
            val handle = RuntimeHandle("http://127.0.0.1:4096", config.username, config.password)
            mutable.value = RuntimeState.Ready(handle)
            return handle
        }

        override suspend fun stop() {
            stops.incrementAndGet()
            mutable.value = RuntimeState.Stopped
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Each controller gets its own scope, cancelled when the test ends.
     *
     * Not `runBlocking`'s scope: the controller keeps a supervisor job as a child
     * of whatever it is given, deliberately, so that cancelling the Activity
     * cancels the runtime. A job that outlives the test body would leave
     * `runBlocking` waiting for a child that never completes.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    private fun controller(runtime: OpencodeRuntime): LocalRuntimeController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return LocalRuntimeController(
            context = context,
            scope = scope,
            versionName = "test",
            runtime = runtime,
            stateDir = File(context.filesDir, "opencode"),
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    @Test
    fun startingTwiceStartsOneServer() = runBlocking {
        val runtime = FakeRuntime()
        val subject = controller(runtime)

        val first = subject.awaitReady()
        val second = subject.awaitReady()

        assertEquals(1, runtime.starts.get())
        assertEquals(first.url, second.url)
    }

    @Test
    fun concurrentCallersJoinOneStart() = runBlocking {
        // The real race: the renderer asks while a reload asks again.
        val gate = CompletableDeferred<Unit>()
        val runtime = FakeRuntime(gate = gate)
        val subject = controller(runtime)

        val waiting = (1..8).map { async(Dispatchers.IO) { subject.awaitReady() } }
        gate.complete(Unit)
        val handles = waiting.awaitAll()

        assertEquals("only one server should ever be started", 1, runtime.starts.get())
        assertEquals(1, handles.map { it.url }.distinct().size)
    }

    @Test
    fun aFailureDoesNotDisableTheRuntimeForTheRestOfTheProcess() = runBlocking {
        // A transient failure - no space, a port briefly taken - must not mean
        // the user has to restart the app before it will try again.
        val failing = FakeRuntime(failWith = IllegalStateException("boom"))
        val subject = controller(failing)

        assertTrue(runCatching { subject.awaitReady() }.isFailure)
        assertTrue(runCatching { subject.awaitReady() }.isFailure)
        assertEquals("it should retry rather than cache the failure", 2, failing.starts.get())
    }

    @Test
    fun eachControllerGetsItsOwnPassword() = runBlocking {
        val first = controller(FakeRuntime()).awaitReady()
        val second = controller(FakeRuntime()).awaitReady()

        assertTrue(!first.password.isNullOrBlank())
        assertNotEquals("a password is per launch, not a constant", first.password, second.password)
    }

    @Test
    fun theHandleCarriesCredentialsAndBindsLoopback() = runBlocking {
        val handle = controller(FakeRuntime()).awaitReady()
        assertEquals("opencode", handle.username)
        assertTrue("the server must be on loopback", handle.url.startsWith("http://127.0.0.1"))
    }

    @Test
    fun stoppingClearsTheStartSoALaterCallRestarts() = runBlocking {
        val runtime = FakeRuntime()
        val subject = controller(runtime)

        subject.awaitReady()
        subject.stop()
        subject.awaitReady()

        assertEquals(1, runtime.stops.get())
        assertEquals(2, runtime.starts.get())
    }

    @Test
    fun theConfigIsLoopbackOnlyAndAllowsTheWebViewOrigin() = runBlocking {
        // Captured rather than asserted indirectly: binding anything but loopback
        // would expose the server to the network, and omitting the origin would
        // make every request from the shell fail CORS.
        var captured: RuntimeConfig? = null
        val runtime = object : OpencodeRuntime {
            private val mutable = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
            override val state: StateFlow<RuntimeState> = mutable.asStateFlow()
            override suspend fun start(config: RuntimeConfig): RuntimeHandle {
                captured = config
                return RuntimeHandle("http://127.0.0.1:4096", config.username, config.password)
            }
            override suspend fun stop() {}
        }

        controller(runtime).awaitReady()

        assertEquals("127.0.0.1", captured?.hostname)
        assertTrue(captured?.corsOrigins?.contains("https://appassets.androidplatform.net") == true)
        assertTrue("the password must reach the server", !captured?.password.isNullOrBlank())
    }
}
