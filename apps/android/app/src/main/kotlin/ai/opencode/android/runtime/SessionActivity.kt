package ai.opencode.android.runtime

import org.json.JSONObject

/**
 * Reads "is the agent working?" out of the server's session status.
 *
 * Separated from the polling that fetches it so the decision can be tested: the
 * network call is uninteresting, but what counts as busy is exactly the sort of
 * thing that goes subtly wrong and then holds a foreground service open forever,
 * or fails to hold one open at all.
 *
 * `/session/status` answers with a map of session id to status. Upstream returns
 * `{"type":"idle"}` for a session doing nothing; anything else means a turn is in
 * flight.
 */
object SessionActivity {

    /** Statuses that mean no work is happening. */
    val IDLE = setOf("idle", "")

    /**
     * True when any session is mid-turn.
     *
     * Unparseable or unexpected input reads as **not busy**. Holding the process
     * awake because a response could not be read is the wrong way to be wrong:
     * the cost of missing a turn is a killed background task, the cost of never
     * releasing is a permanent notification and a battery complaint.
     */
    fun anyRunning(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return false
        return json.keys().asSequence().any { key ->
            val status = json.optJSONObject(key) ?: return@any false
            status.optString("type").lowercase() !in IDLE
        }
    }
}
