package ai.opencode.android

/**
 * Decides what a back press does, given that only the renderer knows.
 *
 * On a phone back is the primary navigation control. What it should undo - an
 * open dialog, the navigation drawer, the previous route - is state that lives
 * in the web app, not in the Activity. `WebView.canGoBack()` cannot answer:
 * the app routes through a memory history precisely so that back does not
 * navigate the document, so the WebView's own history is always empty.
 *
 * So the press is offered to the renderer and the answer comes back over the
 * bridge. That makes the decision asynchronous, which brings two obligations:
 *
 *  - **The user must always be able to leave.** If the renderer does not answer
 *    within [TIMEOUT_MS] the app exits anyway. A wedged web app must never be
 *    able to trap someone in it.
 *  - **A late answer must not act on the wrong screen.** Each press carries a
 *    token, and a reply whose token is not the one outstanding is discarded.
 *
 * The cost is that predictive back cannot preview the app closing: the callback
 * has to stay enabled to be offered the press at all, so the system does not
 * know in advance that the press will exit. Correct navigation is worth more
 * than the preview animation.
 */
class BackCoordinator(
    private val emit: (token: Int) -> Unit,
    private val exit: () -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Unit,
) {
    private var lastToken = 0
    private var pending = NONE

    /** True while a press is waiting on the renderer. */
    val awaitingRenderer: Boolean get() = pending != NONE

    /**
     * Handles a press. When the renderer cannot be reached there is nothing to
     * ask, so the app exits immediately rather than waiting out the timeout.
     */
    fun press(rendererReachable: Boolean) {
        if (!rendererReachable) {
            exit()
            return
        }
        // A second press while one is outstanding supersedes it: the previous
        // token stops being current, so its reply is ignored.
        // Skipping NONE keeps the sentinel from ever being a live token, even
        // after an implausible number of presses wraps the counter.
        lastToken = if (lastToken == Int.MAX_VALUE) NONE + 1 else lastToken + 1
        val token = lastToken
        pending = token
        emit(token)
        schedule(TIMEOUT_MS) {
            if (pending != token) return@schedule
            pending = NONE
            exit()
        }
    }

    /** Records the renderer's answer. Replies for superseded presses are dropped. */
    fun handled(token: Int, handled: Boolean) {
        // The NONE check is not redundant with the token comparison: web content
        // can call this whenever it likes, and a reply carrying the sentinel
        // itself would otherwise match "nothing outstanding" and exit the app.
        if (pending == NONE || token != pending) return
        pending = NONE
        if (!handled) exit()
    }

    companion object {
        /**
         * A bridge round trip is two `postMessage` hops and normally lands in
         * well under a frame. This is the ceiling before the app leaves anyway -
         * long enough that a momentarily busy renderer is not cut off, short
         * enough that an unresponsive one does not feel like a stuck app.
         */
        const val TIMEOUT_MS = 400L
        private const val NONE = 0
    }
}
