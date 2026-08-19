package ai.opencode.android.runtime

/**
 * What the bridge needs from the runtime, and nothing more.
 *
 * `BridgeHost` should not know how the server is started, only that it can ask
 * for an address and be told when the answer changes. Keeping the surface this
 * small is also what lets the bridge be tested without a process.
 */
interface RuntimeController {

    /** Starts the runtime if needed and returns its address once it is answering. */
    suspend fun awaitReady(): RuntimeHandle

    suspend fun stop()
}
