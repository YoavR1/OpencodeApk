package ai.opencode.android.bridge

import org.json.JSONObject

/**
 * The wire contract, kept in one file so it can be read against
 * packages/android/src/bridge.ts side by side.
 *
 * Requests arrive as `{"id":1,"method":"store.get","params":{...}}` and are
 * answered with `{"id":1,"ok":true,"result":...}` or
 * `{"id":1,"ok":false,"error":{"code":"...","message":"..."}}`. Events are
 * pushed as `{"event":"lifecycle","state":"resumed"}` with no id.
 */
object BridgeContract {

    /** Every method the host will answer. Anything else is refused by name. */
    val METHODS: Set<String> = setOf(
        "store.get", "store.set", "store.remove", "store.clear", "store.keys",
        "draft.get", "draft.set", "draft.remove", "draft.putBlob", "draft.getBlob",
        "clipboard.readText", "clipboard.writeText", "clipboard.readImage",
        "share", "openExternal", "pickDirectory", "notify", "restart",
        "back.handled",
        "runtime.await", "runtime.stop",
        "host.info", "defaultServer.get", "defaultServer.set",
    )

    object Errors {
        const val UNKNOWN_METHOD = "UnknownMethod"
        const val INVALID_PARAMS = "InvalidParams"
        const val UNAVAILABLE = "Unavailable"
        const val CANCELLED = "Cancelled"
        const val FAILED = "Failed"
    }

    fun success(id: Int, result: Any?): String =
        JSONObject().put("id", id).put("ok", true).put("result", result ?: JSONObject.NULL).toString()

    fun failure(id: Int, code: String, message: String): String =
        JSONObject()
            .put("id", id)
            .put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    fun event(name: String, fields: Map<String, Any?>): String {
        val json = JSONObject().put("event", name)
        for ((key, value) in fields) json.put(key, value ?: JSONObject.NULL)
        return json.toString()
    }
}

/** A parsed, name-validated request. */
data class BridgeRequest(
    val id: Int,
    val method: String,
    val params: JSONObject,
) {
    /** Reads a required string parameter, or null when absent or the wrong type. */
    fun str(key: String): String? = if (params.has(key) && !params.isNull(key)) params.optString(key) else null

    companion object {
        /**
         * Parses an inbound message.
         *
         * Returns null for anything malformed. Web content can post arbitrary
         * data into this port, so nothing here may assume well-formed input.
         */
        fun parse(raw: String): BridgeRequest? {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val id = json.optInt("id", -1)
            if (id < 0) return null
            val method = json.optString("method").takeIf { it.isNotEmpty() } ?: return null
            val params = json.optJSONObject("params") ?: JSONObject()
            return BridgeRequest(id, method, params)
        }
    }
}
