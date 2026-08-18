package ai.opencode.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back is the control a phone user reaches for most, and this coordinator hands
 * the decision to a renderer that might be slow, wrong, or wedged. The rules
 * that keep that safe - always able to exit, never acting on a stale answer -
 * are what these tests pin.
 *
 * The scheduler is injected, so the timeout is exercised without waiting for it.
 */
private class Harness(private val autoRun: Boolean = false) {
    val emitted = mutableListOf<Int>()
    var exits = 0
    private var scheduled: (() -> Unit)? = null
    var scheduledDelay = 0L
        private set

    val back = BackCoordinator(
        emit = { token -> emitted += token },
        exit = { exits += 1 },
        schedule = { delay, action ->
            scheduledDelay = delay
            if (autoRun) action() else scheduled = action
        },
    )

    /** Runs the pending timeout, as the main thread would once the delay elapses. */
    fun elapse() {
        val action = scheduled
        scheduled = null
        action?.invoke()
    }
}

class BackCoordinatorTest {

    @Test
    fun `a press is offered to the renderer rather than exiting`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        assertEquals(listOf(1), h.emitted)
        assertEquals(0, h.exits)
        assertTrue(h.back.awaitingRenderer)
    }

    @Test
    fun `a handled press does not exit`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.handled(token = 1, handled = true)
        assertEquals(0, h.exits)
        assertFalse(h.back.awaitingRenderer)
    }

    @Test
    fun `an unhandled press exits`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.handled(token = 1, handled = false)
        assertEquals(1, h.exits)
    }

    @Test
    fun `no renderer means exit immediately`() {
        // Before the page loads there is nobody to ask, and waiting out the
        // timeout would make back feel broken.
        val h = Harness()
        h.back.press(rendererReachable = false)
        assertEquals(0, h.emitted.size)
        assertEquals(1, h.exits)
    }

    @Test
    fun `a renderer that never answers still lets the user leave`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        assertEquals(0, h.exits)
        h.elapse()
        assertEquals(1, h.exits)
    }

    @Test
    fun `the timeout is armed with the documented delay`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        assertEquals(BackCoordinator.TIMEOUT_MS, h.scheduledDelay)
    }

    @Test
    fun `a timeout after an answer does not exit`() {
        // The answer arrived in time; the already-scheduled timeout must not
        // then close the app out from under the user.
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.handled(token = 1, handled = true)
        h.elapse()
        assertEquals(0, h.exits)
    }

    @Test
    fun `an answer arriving after the timeout is ignored`() {
        // By then the app is already leaving; acting on it would exit a second
        // time or cancel a press that no longer exists.
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.elapse()
        assertEquals(1, h.exits)
        h.back.handled(token = 1, handled = false)
        assertEquals(1, h.exits)
    }

    @Test
    fun `an answer for a superseded press is discarded`() {
        // Two quick presses: the first press's late "handled" must not cancel
        // the second, which is the one the user is waiting on.
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.press(rendererReachable = true)
        assertEquals(listOf(1, 2), h.emitted)

        h.back.handled(token = 1, handled = true)
        assertTrue("the second press should still be outstanding", h.back.awaitingRenderer)

        h.back.handled(token = 2, handled = false)
        assertEquals(1, h.exits)
    }

    @Test
    fun `a stale timeout does not cut short a later press`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.press(rendererReachable = true)
        // Only the newest timeout is outstanding in the harness, but the
        // superseded token must be inert regardless of firing order.
        h.back.handled(token = 1, handled = false)
        assertEquals(0, h.exits)
    }

    @Test
    fun `an unsolicited answer is ignored`() {
        // Web content can send back.handled whenever it likes; without a press
        // outstanding it must do nothing at all.
        val h = Harness()
        h.back.handled(token = 1, handled = false)
        assertEquals(0, h.exits)
        h.back.handled(token = 0, handled = false)
        assertEquals(0, h.exits)
    }

    @Test
    fun `tokens are not reused across presses`() {
        val h = Harness(autoRun = true)
        repeat(3) { h.back.press(rendererReachable = true) }
        assertEquals(listOf(1, 2, 3), h.emitted)
    }

    @Test
    fun `each press gets its own decision`() {
        val h = Harness()
        h.back.press(rendererReachable = true)
        h.back.handled(token = 1, handled = true)
        h.back.press(rendererReachable = true)
        h.back.handled(token = 2, handled = true)
        assertEquals(0, h.exits)
        h.back.press(rendererReachable = true)
        h.back.handled(token = 3, handled = false)
        assertEquals(1, h.exits)
    }
}
