package ai.opencode.android

import ai.opencode.android.util.SafeLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app will hold a per-launch server password (M7) and provider credentials
 * (M10). Redaction is enforced at the logging boundary, so it is tested there.
 */
class SafeLogTest {

    @Test
    fun `password assignments are redacted`() {
        assertEquals("password=***", SafeLog.redact("password=hunter2"))
        assertEquals("password: ***", SafeLog.redact("password: hunter2"))
        assertEquals("Password=***", SafeLog.redact("Password=hunter2"))
    }

    @Test
    fun `token secret and api key are redacted`() {
        assertFalse(SafeLog.redact("token=abc123").contains("abc123"))
        assertFalse(SafeLog.redact("secret=abc123").contains("abc123"))
        assertFalse(SafeLog.redact("api_key=abc123").contains("abc123"))
        assertFalse(SafeLog.redact("apikey=abc123").contains("abc123"))
        assertFalse(SafeLog.redact("Authorization: Basic dXNlcjpwdw==").contains("dXNlcjpwdw"))
    }

    @Test
    fun `credentials embedded in a url are redacted`() {
        assertEquals(
            "connecting to https://opencode:***@127.0.0.1:4096/api/health",
            SafeLog.redact("connecting to https://opencode:s3cr3t@127.0.0.1:4096/api/health"),
        )
    }

    @Test
    fun `redaction keeps surrounding context`() {
        val out = SafeLog.redact("starting server port=4096 password=s3cr3t host=127.0.0.1")
        assertTrue(out.contains("port=4096"))
        assertTrue(out.contains("host=127.0.0.1"))
        assertFalse(out.contains("s3cr3t"))
    }

    @Test
    fun `ordinary messages are untouched`() {
        val message = "loading https://appassets.androidplatform.net/assets/index.html"
        assertEquals(message, SafeLog.redact(message))
    }

    @Test
    fun `words merely containing a sensitive substring are not redacted`() {
        // "passwordless" should not trip the word-boundary rule for "password".
        assertEquals("mode=passwordless", SafeLog.redact("mode=passwordless"))
    }
}
