package ai.opencode.android.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * This decides whether a foreground service is held open, so both ways of being
 * wrong are expensive: missing a turn gets background work killed, and never
 * releasing leaves a permanent notification and a battery complaint.
 */
@RunWith(RobolectricTestRunner::class)
class SessionActivityTest {

    @Test
    fun noSessionsMeansNotBusy() {
        assertFalse(SessionActivity.anyRunning("{}"))
    }

    @Test
    fun idleSessionsMeanNotBusy() {
        assertFalse(SessionActivity.anyRunning("""{"ses_a":{"type":"idle"},"ses_b":{"type":"idle"}}"""))
    }

    @Test
    fun aRunningSessionMeansBusy() {
        assertTrue(SessionActivity.anyRunning("""{"ses_a":{"type":"running"}}"""))
    }

    @Test
    fun oneRunningAmongManyIdleMeansBusy() {
        assertTrue(
            SessionActivity.anyRunning(
                """{"ses_a":{"type":"idle"},"ses_b":{"type":"running"},"ses_c":{"type":"idle"}}""",
            ),
        )
    }

    @Test
    fun anUnfamiliarStatusIsTreatedAsWork() {
        // Upstream may add states. A new one almost certainly means something is
        // happening, and treating it as idle would kill the work it describes.
        assertTrue(SessionActivity.anyRunning("""{"ses_a":{"type":"retrying"}}"""))
        assertTrue(SessionActivity.anyRunning("""{"ses_a":{"type":"queued"}}"""))
    }

    @Test
    fun theStatusIsMatchedCaseInsensitively() {
        assertFalse(SessionActivity.anyRunning("""{"ses_a":{"type":"IDLE"}}"""))
    }

    @Test
    fun unreadableInputIsNotBusy() {
        // A failed or truncated response must not pin the process awake.
        for (body in listOf(null, "", "   ", "not json", "[]", "null", """{"ses_a":"idle"}""")) {
            assertFalse("expected not-busy for: $body", SessionActivity.anyRunning(body))
        }
    }

    @Test
    fun aStatusWithoutATypeIsNotBusy() {
        assertFalse(SessionActivity.anyRunning("""{"ses_a":{}}"""))
    }
}
