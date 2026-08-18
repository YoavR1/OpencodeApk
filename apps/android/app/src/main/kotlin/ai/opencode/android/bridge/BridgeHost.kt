package ai.opencode.android.bridge

import androidx.appcompat.app.AppCompatActivity
import ai.opencode.android.BuildConfig
import ai.opencode.android.platform.DirectoryPicker
import ai.opencode.android.platform.DraftStore
import ai.opencode.android.platform.Notifications
import ai.opencode.android.platform.PreferenceStore
import ai.opencode.android.platform.SystemIntegration
import ai.opencode.android.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dispatches bridge requests to the capability handlers.
 *
 * One `when` over the method name, so the whole native surface reachable from web
 * content is visible in a single place and can be read against
 * packages/android/src/bridge.ts.
 *
 * Two rules hold throughout:
 *  - unknown methods are refused by name, not ignored, so a typo surfaces as an
 *    error in the renderer instead of a promise that never settles;
 *  - handlers that touch disk run off the main thread, because a janky UI on a
 *    phone is a defect, not a nuisance.
 */
class BridgeHost(
    private val activity: AppCompatActivity,
    private val scope: CoroutineScope,
    private val send: (String) -> Unit,
    private val onBackHandled: (token: Int, handled: Boolean) -> Unit = { _, _ -> },
) {
    private val prefs = PreferenceStore(activity.applicationContext)
    private val drafts = DraftStore(activity.applicationContext)
    private val system = SystemIntegration(activity.applicationContext)
    private val notifications = Notifications(activity.applicationContext)
    private val picker = DirectoryPicker(activity)

    fun handle(raw: String) {
        val request = BridgeRequest.parse(raw)
        if (request == null) {
            SafeLog.w("discarded a malformed bridge message")
            return
        }
        if (request.method !in BridgeContract.METHODS) {
            send(BridgeContract.failure(request.id, BridgeContract.Errors.UNKNOWN_METHOD, "unknown method ${request.method}"))
            return
        }
        scope.launch { dispatch(request) }
    }

    private suspend fun dispatch(request: BridgeRequest) {
        val id = request.id
        try {
            when (request.method) {
                "host.info" -> send(
                    BridgeContract.success(
                        id,
                        JSONObject()
                            .put("versionName", BuildConfig.VERSION_NAME)
                            .put("debug", BuildConfig.DEBUG)
                            .put("notificationsPermitted", notifications.permitted()),
                    ),
                )

                // ---- preferences ----------------------------------------------
                "store.get" -> withIo(id) {
                    val name = request.str("name") ?: return@withIo invalid(id, "name")
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    send(BridgeContract.success(id, prefs.get(name, key)))
                }
                "store.set" -> withIo(id) {
                    val name = request.str("name") ?: return@withIo invalid(id, "name")
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    val value = request.str("value") ?: return@withIo invalid(id, "value")
                    prefs.set(name, key, value)
                    send(BridgeContract.success(id, true))
                }
                "store.remove" -> withIo(id) {
                    val name = request.str("name") ?: return@withIo invalid(id, "name")
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    prefs.remove(name, key)
                    send(BridgeContract.success(id, true))
                }
                "store.clear" -> withIo(id) {
                    val name = request.str("name") ?: return@withIo invalid(id, "name")
                    prefs.clear(name)
                    send(BridgeContract.success(id, true))
                }
                "store.keys" -> withIo(id) {
                    val name = request.str("name") ?: return@withIo invalid(id, "name")
                    send(BridgeContract.success(id, JSONArray(prefs.keys(name))))
                }

                // ---- drafts ---------------------------------------------------
                "draft.get" -> withIo(id) {
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    send(BridgeContract.success(id, drafts.get(key)))
                }
                "draft.set" -> withIo(id) {
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    val value = request.str("value") ?: return@withIo invalid(id, "value")
                    drafts.set(key, value)
                    send(BridgeContract.success(id, true))
                }
                "draft.remove" -> withIo(id) {
                    val key = request.str("key") ?: return@withIo invalid(id, "key")
                    drafts.remove(key)
                    send(BridgeContract.success(id, true))
                }
                "draft.putBlob" -> withIo(id) {
                    val base64 = request.str("base64") ?: return@withIo invalid(id, "base64")
                    val type = request.str("type") ?: ""
                    send(BridgeContract.success(id, drafts.putBlob(base64, type)))
                }
                "draft.getBlob" -> withIo(id) {
                    val blobId = request.str("id") ?: return@withIo invalid(id, "id")
                    val blob = drafts.getBlob(blobId)
                    send(
                        BridgeContract.success(
                            id,
                            blob?.let { JSONObject().put("base64", it.first).put("type", it.second) },
                        ),
                    )
                }

                // ---- system ---------------------------------------------------
                "clipboard.readText" -> send(BridgeContract.success(id, system.readText()))
                "clipboard.writeText" -> {
                    val text = request.str("text") ?: return invalid(id, "text")
                    send(BridgeContract.success(id, system.writeText(text)))
                }
                "clipboard.readImage" -> withIo(id) {
                    val image = system.readImage()
                    send(
                        BridgeContract.success(
                            id,
                            image?.let { JSONObject().put("base64", it.first).put("type", it.second) },
                        ),
                    )
                }
                "share" -> {
                    val text = request.str("text") ?: return invalid(id, "text")
                    send(BridgeContract.success(id, system.share(activity, text, request.str("title"))))
                }
                "openExternal" -> {
                    val url = request.str("url") ?: return invalid(id, "url")
                    send(BridgeContract.success(id, system.openExternal(url)))
                }
                "pickDirectory" -> send(BridgeContract.success(id, picker.pick()))
                "notify" -> {
                    val title = request.str("title") ?: return invalid(id, "title")
                    val body = request.str("body") ?: ""
                    val tag = request.str("tag") ?: return invalid(id, "tag")
                    send(BridgeContract.success(id, notifications.post(title, body, tag)))
                }
                "restart" -> {
                    send(BridgeContract.success(id, true))
                    activity.recreate()
                }

                // ---- navigation -----------------------------------------------
                "back.handled" -> {
                    // Answered on the main thread: the reply races a timeout that
                    // exits the app, so it must not queue behind disk work.
                    val token = request.params.optInt("token", -1)
                    if (token < 0) return invalid(id, "token")
                    if (!request.params.has("handled")) return invalid(id, "handled")
                    onBackHandled(token, request.params.optBoolean("handled", false))
                    send(BridgeContract.success(id, true))
                }

                // ---- selected server ------------------------------------------
                "defaultServer.get" -> withIo(id) {
                    send(BridgeContract.success(id, prefs.get(SERVER_STORE, SERVER_KEY)))
                }
                "defaultServer.set" -> withIo(id) {
                    val url = request.str("url")
                    if (url == null) prefs.remove(SERVER_STORE, SERVER_KEY)
                    else prefs.set(SERVER_STORE, SERVER_KEY, url)
                    send(BridgeContract.success(id, true))
                }

                else -> send(
                    BridgeContract.failure(id, BridgeContract.Errors.UNKNOWN_METHOD, "unhandled ${request.method}"),
                )
            }
        } catch (error: Throwable) {
            // A handler throwing must still settle the renderer's promise, or the
            // UI waits forever on something that already failed.
            SafeLog.w("bridge handler failed for ${request.method}", error)
            send(BridgeContract.failure(id, BridgeContract.Errors.FAILED, error.message ?: "handler failed"))
        }
    }

    /** Runs disk-touching work off the main thread. */
    private suspend inline fun withIo(id: Int, crossinline block: suspend () -> Unit) {
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (error: Throwable) {
            SafeLog.w("bridge io failed", error)
            send(BridgeContract.failure(id, BridgeContract.Errors.FAILED, error.message ?: "io failed"))
        }
    }

    private fun invalid(id: Int, param: String) =
        send(BridgeContract.failure(id, BridgeContract.Errors.INVALID_PARAMS, "missing or invalid '$param'"))

    // ---- host-initiated events -------------------------------------------------

    fun emitLifecycle(state: String) = send(BridgeContract.event("lifecycle", mapOf("state" to state)))

    fun emitKeyboard(heightCssPx: Int) = send(BridgeContract.event("keyboard", mapOf("height" to heightCssPx)))

    fun emitNotificationClicked(tag: String) =
        send(BridgeContract.event("notification.clicked", mapOf("tag" to tag)))

    fun emitBack(token: Int) = send(BridgeContract.event("back", mapOf("token" to token)))

    private companion object {
        const val SERVER_STORE = "servers"
        const val SERVER_KEY = "default-server"
    }
}
