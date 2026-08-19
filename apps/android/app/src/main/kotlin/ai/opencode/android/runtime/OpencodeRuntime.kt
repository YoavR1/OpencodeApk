package ai.opencode.android.runtime

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam that keeps a remote server and an on-device one on one code path.
 *
 * The UI cannot tell the difference: both end as a `ServerConnection` pointing at
 * an HTTP address with credentials attached. Only the value differs, which is the
 * property `.claude/rules/architecture.md` A3 exists to protect.
 */

/** Where the server should listen and where it may keep its state. */
data class RuntimeConfig(
    val hostname: String = "127.0.0.1",
    /** 0 asks the OS for an ephemeral port, which is resolved during start. */
    val port: Int = 0,
    val username: String = "opencode",
    /** Generated per launch, held in memory, never written down. */
    val password: String,
    val stateDir: File,
    /** The WebView origin, so the server will answer requests from the shell. */
    val corsOrigins: List<String>,
)

/** Mirrors upstream's `ServerReadyData`, so the renderer needs no new shape. */
data class RuntimeHandle(val url: String, val username: String?, val password: String?)

/**
 * What the runtime is doing, as something the UI can render.
 *
 * `Degraded` is separate from `Failed` on purpose: a server that started and then
 * stopped answering is a different situation from one that never started, and the
 * user can act on them differently.
 */
sealed interface RuntimeState {
    data object Stopped : RuntimeState

    data object Starting : RuntimeState

    data class Ready(val handle: RuntimeHandle) : RuntimeState

    /** Running, but not answering health checks. */
    data class Degraded(val handle: RuntimeHandle, val reason: String) : RuntimeState

    data class Failed(val reason: String) : RuntimeState

    data object Stopping : RuntimeState

    /** The name alone, for logs and for the bridge. Never carries the password. */
    val name: String
        get() = when (this) {
            is Stopped -> "stopped"
            is Starting -> "starting"
            is Ready -> "ready"
            is Degraded -> "degraded"
            is Failed -> "failed"
            is Stopping -> "stopping"
        }
}

interface OpencodeRuntime {
    val state: StateFlow<RuntimeState>

    /** Starts the server and returns once it answers a health check. */
    suspend fun start(config: RuntimeConfig): RuntimeHandle

    suspend fun stop()
}
