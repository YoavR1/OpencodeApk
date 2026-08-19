package ai.opencode.android.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The M6 feasibility questions, answered from inside the app's own sandbox.
 *
 * These have to run as the app's uid. Anything measured through `adb shell` runs
 * as the far more privileged `shell` user and would answer a different question:
 * `/data/local/tmp` is exec-permitted for `shell` and irrelevant to what the app
 * can do.
 *
 * The executable under test is the device's own `/system/bin/sh`, copied into
 * `debug/jniLibs/arm64-v8a/libspikesh.so` by
 * `spike/m6/provision-exec-fixture.sh`. It is a real dynamically-linked arm64
 * Bionic executable, so it exercises exactly the loader path a bundled runtime
 * would take - and taking it off the connected device avoids both redistributing
 * a vendor binary and pulling a third party into the build. The fixture is
 * git-ignored; run the script once before these tests.
 *
 * See docs/LOCAL_RUNTIME_SPIKE.md.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeFeasibilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    /** Q3: can the app launch a shell command at all? */
    @Test
    fun theAppCanLaunchAShellCommand() {
        val (code, output) = run("/system/bin/sh", "-c", "echo shell-ok")
        assertEquals("sh should exit cleanly, got: $output", 0, code)
        assertEquals("shell-ok", output)
    }

    /** Q4 part 1: an arm64 executable shipped as a jniLib runs from nativeLibraryDir. */
    @Test
    fun anExecutableShippedAsAJniLibRuns() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libspikesh.so")
        assertTrue(
            "libspikesh.so is missing from ${binary.parent}. Either the fixture was " +
                "never provisioned (run spike/m6/provision-exec-fixture.sh) or native " +
                "libraries are not being extracted (useLegacyPackaging).",
            binary.exists(),
        )
        assertTrue("it must be executable", binary.canExecute())

        val (code, output) = run(binary.absolutePath, "-c", "echo jnilib-exec-ok")
        assertEquals("exec failed: $output", 0, code)
        assertEquals("jnilib-exec-ok", output)
    }

    /**
     * The W^X constraint, measured rather than assumed.
     *
     * From API 29 an app may not execute a file it can write. This is the single
     * fact that decides how a runtime has to be packaged: not downloaded at first
     * launch into `filesDir`, but shipped in the APK as a `lib*.so`.
     */
    @Test
    fun anExecutableInTheDataDirectoryCannotBeRun() {
        val source = File(context.applicationInfo.nativeLibraryDir, "libspikesh.so")
        val copy = File(context.filesDir, "spike-copy")
        source.copyTo(copy, overwrite = true)
        copy.setExecutable(true, true)

        val failure = runCatching { run(copy.absolutePath, "-c", "echo should-not-run") }.exceptionOrNull()

        assertNotNull(
            "executing from filesDir should have been refused, but it ran. " +
                "If this ever passes, W^X has changed and the packaging constraint should be revisited.",
            failure,
        )
        // The kernel reports EACCES for a noexec/untrusted mount; the message is
        // recorded rather than asserted, because its wording is not contractual.
        println("[spike] filesDir exec refused with: ${failure!!.message}")
    }

    /** Q1 precondition: the app can bind a loopback port, which is where the server would live. */
    @Test
    fun theAppCanBindALoopbackPort() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
            assertTrue("an ephemeral port should have been assigned", socket.localPort > 0)
            assertEquals("127.0.0.1", socket.inetAddress.hostAddress)
            println("[spike] bound loopback port ${socket.localPort}")
        }
    }

    /** Q2: a runtime would need ordinary file IO in the app's private storage. */
    @Test
    fun theAppCanReadAndWriteItsOwnStorage() {
        val file = File(context.filesDir, "spike-io.txt")
        file.writeText("filesystem-ok")
        assertEquals("filesystem-ok", file.readText())
        assertTrue(file.delete())
    }

    /** What a bundled runtime would inherit: no root, and no need for it. */
    @Test
    fun theAppIsNotRunningAsRoot() {
        val (_, output) = run("/system/bin/sh", "-c", "id -u")
        assertTrue("expected a non-root uid, got '$output'", output.toIntOrNull()?.let { it != 0 } == true)
        println("[spike] app uid: $output")
    }
}
