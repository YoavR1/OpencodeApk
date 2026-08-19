package ai.opencode.android.runtime

import android.content.Context
import ai.opencode.android.util.SafeLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs the OpenCode server as a separate Node process on the device.
 *
 * This is the desktop sidecar pattern (`packages/desktop/src/main/sidecar.ts`)
 * with a different binary: generate a per-launch password, start the server on
 * loopback, wait for it to report ready, hand the address to the UI, and stop it
 * on shutdown. ADR-0005 chose that pattern; ADR-0022 chose Node as the binary.
 *
 * Everything here runs off the main thread. Starting a process, reading its
 * stdout and polling health are all blocking, and the UI thread does none of it.
 */
class EmbeddedProcessRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val assets: RuntimeAssets,
) : OpencodeRuntime {

    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var process: Process? = null
    private var watchdog: Job? = null

    override suspend fun start(config: RuntimeConfig): RuntimeHandle = withContext(Dispatchers.IO) {
        _state.value = RuntimeState.Starting

        try {
            val home = assets.install()
            val binary = File(context.applicationInfo.nativeLibraryDir, NODE)
            check(binary.exists()) {
                "$NODE is missing from ${binary.parent}. Run scripts/runtime/prepare-android-runtime.py " +
                    "and rebuild; see docs/LOCAL_RUNTIME_SPIKE.md."
            }

            val started = launchProcess(binary, home, config)
            process = started

            val handle = awaitReady(started, config)
            _state.value = RuntimeState.Ready(handle)
            watch(started, handle, config)
            handle
        } catch (error: Throwable) {
            SafeLog.w("runtime failed to start", error)
            stopProcess()
            _state.value = RuntimeState.Failed(error.message ?: error.javaClass.simpleName)
            throw error
        }
    }

    private fun launchProcess(binary: File, home: File, config: RuntimeConfig): Process {
        val state = config.stateDir.apply { mkdirs() }
        val tmp = File(state, "tmp").apply { mkdirs() }

        val command = listOf(
            binary.absolutePath,
            "launch.mjs",
            "--hostname=${config.hostname}",
            "--port=${config.port}",
            "--cors=${config.corsOrigins.joinToString(",")}",
        )

        val builder = ProcessBuilder(command).directory(home).redirectErrorStream(true)
        builder.environment().apply {
            // The libraries were renamed to satisfy Android's packaging rules and
            // live beside the binary; point the loader at them explicitly rather
            // than relying on an inherited path.
            put("LD_LIBRARY_PATH", binary.parent)

            // The server reads its own credentials from the environment. The
            // password is generated per launch and never persisted, so it exists
            // only here and in the handle handed to the WebView.
            put("OPENCODE_SERVER_PASSWORD", config.password)
            put("OPENCODE_SERVER_USERNAME", config.username)

            // Everything below is state that would otherwise land outside the app
            // sandbox. HOME especially: without it "~" resolves to "/" and the
            // bootstrap fails trying to create /.config (M6).
            put("HOME", state.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("XDG_CONFIG_HOME", File(state, ".config").absolutePath)
            put("XDG_DATA_HOME", File(state, ".local/share").absolutePath)
            put("XDG_STATE_HOME", File(state, ".local/state").absolutePath)
            put("XDG_CACHE_HOME", File(state, ".cache").absolutePath)

            // Android's shell, not the one this Node build was compiled against.
            put("SHELL", ANDROID_SHELL)
        }

        SafeLog.d("starting runtime from ${binary.name}")
        return builder.start()
    }

    /**
     * Waits for the launcher's `ready` line.
     *
     * The launcher reports the resolved address on stdout as one JSON object per
     * event, so the port chosen by the OS comes back without guessing. Output is
     * drained regardless: a process whose stdout fills its pipe buffer stops.
     */
    private suspend fun awaitReady(process: Process, config: RuntimeConfig): RuntimeHandle {
        val handle = withTimeoutOrNull(START_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val reader = process.inputStream.bufferedReader()
                var found: RuntimeHandle? = null
                while (found == null) {
                    val line = reader.readLine() ?: break
                    found = parseReady(line, config)
                }
                // Keep draining in the background so the process is never blocked
                // on a full pipe, and so its diagnostics reach logcat.
                scope.launch(Dispatchers.IO) { drain(reader) }
                found
            }
        }

        return handle ?: error(
            if (process.isAlive) "the runtime did not report ready within ${START_TIMEOUT_MS}ms"
            else "the runtime exited with code ${process.exitValue()} before reporting ready",
        )
    }

    private fun parseReady(line: String, config: RuntimeConfig): RuntimeHandle? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: run {
            // Not our protocol; it is the runtime's own logging.
            SafeLog.d("runtime: ${line.take(200)}")
            return null
        }
        return when (json.optString("event")) {
            "ready" -> RuntimeHandle(
                url = json.getString("url"),
                username = config.username,
                password = config.password,
            ).also { SafeLog.d("runtime ready in ${json.optInt("startupMs", -1)}ms") }

            "error" -> error("the runtime reported: ${json.optString("message")}")
            else -> null
        }
    }

    private fun drain(reader: java.io.BufferedReader) {
        runCatching {
            while (true) {
                val line = reader.readLine() ?: break
                // SafeLog redacts credential-shaped strings; the runtime's output
                // is not ours and may contain anything.
                SafeLog.d("runtime: ${line.take(400)}")
            }
        }
    }

    /**
     * Notices when the runtime dies or stops answering.
     *
     * A process that exits takes the UI's server with it, and saying so is much
     * better than the UI retrying against an address nothing is listening on.
     */
    private fun watch(process: Process, handle: RuntimeHandle, config: RuntimeConfig) {
        watchdog?.cancel()
        watchdog = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)

                if (!process.isAlive) {
                    val code = runCatching { process.exitValue() }.getOrNull()
                    SafeLog.w("runtime exited with code $code")
                    _state.value = RuntimeState.Failed("the runtime stopped unexpectedly (exit $code)")
                    return@launch
                }

                val healthy = healthy(handle)
                val current = _state.value
                when {
                    healthy && current is RuntimeState.Degraded -> {
                        SafeLog.d("runtime healthy again")
                        _state.value = RuntimeState.Ready(handle)
                    }
                    !healthy && current is RuntimeState.Ready -> {
                        SafeLog.w("runtime stopped answering health checks")
                        _state.value = RuntimeState.Degraded(handle, "not answering health checks")
                    }
                }
            }
        }
    }

    /** One health probe, mirroring what desktop polls. */
    fun healthy(handle: RuntimeHandle): Boolean {
        for (path in HEALTH_PATHS) {
            val connection = runCatching {
                (URL("${handle.url}$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = HEALTH_TIMEOUT_MS
                    readTimeout = HEALTH_TIMEOUT_MS
                    requestMethod = "GET"
                    handle.password?.let {
                        val token = android.util.Base64.encodeToString(
                            "${handle.username ?: "opencode"}:$it".toByteArray(),
                            android.util.Base64.NO_WRAP,
                        )
                        setRequestProperty("Authorization", "Basic $token")
                    }
                }
            }.getOrNull() ?: continue

            val code = runCatching { connection.responseCode }.getOrNull()
            runCatching { connection.disconnect() }
            if (code != null && code in 200..299) return true
        }
        return false
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        if (_state.value is RuntimeState.Stopped) return@withContext
        _state.value = RuntimeState.Stopping
        watchdog?.cancel()
        watchdog = null
        stopProcess()
        _state.value = RuntimeState.Stopped
    }

    private fun stopProcess() {
        val current = process ?: return
        process = null
        runCatching {
            current.destroy()
            // Give it a moment to close SQLite cleanly before insisting.
            if (!current.waitFor(STOP_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                current.destroyForcibly()
            }
        }.onFailure { SafeLog.w("failed to stop the runtime", it) }
    }

    private companion object {
        const val NODE = "libnode.so"
        const val ANDROID_SHELL = "/system/bin/sh"

        /** A cold start on a phone is slower than on a laptop; measured at ~3s in M6. */
        const val START_TIMEOUT_MS = 60_000L
        const val STOP_GRACE_MS = 3_000L
        const val HEALTH_INTERVAL_MS = 5_000L
        const val HEALTH_TIMEOUT_MS = 4_000

        /** `/api/health` first, as desktop does, falling back to the global route. */
        val HEALTH_PATHS = listOf("/api/health", "/global/health")
    }
}
