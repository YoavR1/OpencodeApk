package ai.opencode.android.util

import android.util.Log
import ai.opencode.android.BuildConfig

/**
 * Debug logging with a hard rule: secrets never reach logcat.
 *
 * The app handles a per-launch server password (M7) and provider credentials
 * (M10). Android logs are readable by the user and by anything with the right
 * tooling, so redaction is applied at the logging boundary rather than trusted to
 * every call site.
 *
 * See .claude/rules/android.md N8 and docs/IMPLEMENTATION_PLAN.md M10.
 */
object SafeLog {

    const val TAG: String = "OpenCode"

    /** Verbose/debug logging is compiled in but silent in release builds. */
    private val debugEnabled: Boolean get() = BuildConfig.DEBUG

    fun d(message: String) {
        if (debugEnabled) Log.d(TAG, redact(message))
    }

    fun i(message: String) {
        Log.i(TAG, redact(message))
    }

    fun w(message: String, error: Throwable? = null) {
        if (error == null) Log.w(TAG, redact(message)) else Log.w(TAG, redact(message), error)
    }

    fun e(message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, redact(message)) else Log.e(TAG, redact(message), error)
    }

    /**
     * Replaces the value of anything that looks like a credential with `***`.
     *
     * Covers the shapes this project actually produces:
     *  - `key=value` and `key: value` for password/token/secret/apikey
     *  - a whole `Authorization:` header value
     *  - HTTP Basic credentials embedded in a URL (`https://user:pass@host`)
     *
     * Pure and side-effect free so it can be unit tested directly.
     */
    fun redact(message: String): String {
        // Order matters: the Authorization rule consumes to end of line, because
        // its value contains a space ("Basic <token>") and a token-shaped matcher
        // would stop at the scheme and leak the credential itself.
        var result = AUTHORIZATION_HEADER.replace(message) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}***"
        }
        result = SENSITIVE_ASSIGNMENT.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}***"
        }
        result = URL_CREDENTIALS.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}:***@"
        }
        return result
    }

    private val AUTHORIZATION_HEADER = Regex(
        """\b(authorization)\b(\s*[=:]\s*).+""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    private val SENSITIVE_ASSIGNMENT = Regex(
        """\b(password|passwd|secret|token|apikey|api_key)\b(\s*[=:]\s*)\S+""",
        RegexOption.IGNORE_CASE,
    )

    private val URL_CREDENTIALS = Regex("""\b(https?://)([^/\s:@]+):[^/\s@]+@""")
}
