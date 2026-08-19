package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The state names cross the bridge and are rendered by the UI, so they are a
 * contract rather than a debugging convenience.
 */
class RuntimeStateTest {

    private val handle = RuntimeHandle("http://127.0.0.1:4096", "opencode", "secret")

    @Test
    fun everyStateHasAStableName() {
        assertEquals("stopped", RuntimeState.Stopped.name)
        assertEquals("starting", RuntimeState.Starting.name)
        assertEquals("ready", RuntimeState.Ready(handle).name)
        assertEquals("degraded", RuntimeState.Degraded(handle, "no answer").name)
        assertEquals("failed", RuntimeState.Failed("boom").name)
        assertEquals("stopping", RuntimeState.Stopping.name)
    }

    @Test
    fun theNamesAreAllDistinct() {
        val names = listOf(
            RuntimeState.Stopped,
            RuntimeState.Starting,
            RuntimeState.Ready(handle),
            RuntimeState.Degraded(handle, ""),
            RuntimeState.Failed(""),
            RuntimeState.Stopping,
        ).map { it.name }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun aNameNeverLeaksThePassword() {
        // The name is what crosses the bridge and reaches logs. The password
        // reaches the client and nowhere else.
        for (state in listOf(RuntimeState.Ready(handle), RuntimeState.Degraded(handle, "x"))) {
            assertFalse(state.name.contains("secret"))
        }
    }

    @Test
    fun degradedIsDistinctFromFailed() {
        // A server that started and then stopped answering is recoverable; one
        // that never started is a different situation, and the UI should be able
        // to tell them apart rather than showing one message for both.
        val degraded: RuntimeState = RuntimeState.Degraded(handle, "no answer")
        val failed: RuntimeState = RuntimeState.Failed("no answer")
        assertFalse(degraded == failed)
        assertFalse(degraded.name == failed.name)
    }
}
