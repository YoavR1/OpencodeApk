package ai.opencode.android.runtime

import android.content.Context
import ai.opencode.android.util.SafeLog
import ai.opencode.android.web.WebOrigin
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the on-device runtime for the lifetime of the app process.
 *
 * Start is idempotent and safe to call concurrently: the renderer asks for an
 * address as soon as it loads, and a reload or a second caller must join the
 * start already in progress rather than launching a second server.
 *
 * The password is generated here, once per process, and never written to disk -
 * the same rule the desktop sidecar follows. It reaches the WebView through the
 * bridge and lives nowhere else.
 */
class LocalRuntimeController(
    context: Context,
    scope: CoroutineScope,
    versionName: String,
    runtime: OpencodeRuntime? = null,
    private val stateDir: File = File(context.applicationContext.filesDir, "opencode"),
) : RuntimeController {

    /**
     * Runtime work runs under a supervisor.
     *
     * Without one, a failed start propagates out of `async` into the parent -
     * `lifecycleScope` - and cancels every other coroutine the Activity owns,
     * including the bridge. A runtime that will not start is a recoverable
     * situation; taking the UI down with it is not. A unit test caught this.
     *
     * The supervisor is still a child of the caller's job, so cancellation flows
     * downward as it should: when the Activity goes, so does the runtime.
     */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val runtime: OpencodeRuntime = runtime ?: EmbeddedProcessRuntime(
        context = context.applicationContext,
        scope = work,
        assets = RuntimeAssets(context.applicationContext, versionName),
    )

    val state: StateFlow<RuntimeState> get() = runtime.state

    /** True while a turn is in flight; see EmbeddedProcessRuntime.busy. */
    val busy: StateFlow<Boolean> get() = runtime.busy

    private val password: String = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private var starting: Deferred<RuntimeHandle>? = null

    override suspend fun awaitReady(): RuntimeHandle {
        (runtime.state.value as? RuntimeState.Ready)?.let { return it.handle }

        val pending = mutex.withLock {
            // A completed start describes a process that was alive when it
            // finished - not one that is alive now. Reusing it after the runtime
            // died hands the caller a dead address instantly and starts nothing,
            // which is exactly what happened on the device when the runtime was
            // killed: the renderer asked to restart and was given the corpse.
            //
            // So a cached start is reusable only while it is still running (join
            // it) or while the process it produced is still there.
            val reusable = starting?.takeIf { it.isActive || runtime.state.value.alive }
            reusable ?: work.async(Dispatchers.IO) {
                runtime.start(
                    RuntimeConfig(
                        // Loopback only. Anything else would expose the server to
                        // the network, and other apps on the device already share
                        // loopback - which is why the password is not optional.
                        hostname = "127.0.0.1",
                        port = 0,
                        password = password,
                        stateDir = stateDir,
                        // So the WebView's own origin is allowed through CORS
                        // without depending on the upstream allowlist.
                        corsOrigins = listOf(WebOrigin.ORIGIN),
                    ),
                )
            }.also { starting = it }
        }

        return try {
            pending.await()
        } catch (error: Throwable) {
            // Let the next caller try again rather than caching the failure
            // forever; a transient failure should not disable the runtime for
            // the rest of the process.
            mutex.withLock { if (starting === pending) starting = null }
            throw error
        }
    }

    override suspend fun stop() {
        mutex.withLock { starting = null }
        runtime.stop()
        SafeLog.d("runtime stopped")
    }
}
