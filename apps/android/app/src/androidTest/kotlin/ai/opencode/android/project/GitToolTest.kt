package ai.opencode.android.project

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opencode.android.runtime.RuntimeTools
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Git, in a real project, on the device.
 *
 * This is the part of M8 that cannot be unit tested: whether the bundled binary
 * actually runs, finds its helpers under the names it expects, and can complete a
 * commit without a passwd entry or a system config to read.
 *
 * Skipped rather than failed when git is not packaged - a build without the
 * runtime is legitimate (ADR-0023), and failing here would report the build
 * configuration rather than a defect.
 */
@RunWith(AndroidJUnit4::class)
class GitToolTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var bin: File
    private lateinit var project: File

    private val packaged: Boolean
        get() = File(context.applicationInfo.nativeLibraryDir, "libgit.so").exists()

    @Before
    fun setUp() {
        // Link the tools the same way the runtime does, into a directory of this
        // test's own so it does not disturb a running app.
        val root = File(context.filesDir, "git-test").apply { mkdirs() }
        File(root, RuntimeTools.MANIFEST).writeText(
            """{"links":{"git":"libgit.so","git-remote-http":"libgit-remote-http.so","git-remote-https":"libgit-remote-http.so"}}""",
        )
        bin = RuntimeTools(File(context.applicationInfo.nativeLibraryDir), root).install() ?: File(root, "bin")

        project = ProjectStore(File(context.filesDir, "git-test-projects").apply { mkdirs() })
            .create("Git Test").directory
        project.deleteRecursively()
        project.mkdirs()
    }

    private fun git(vararg args: String, cwd: File = project): Pair<Int, String> {
        val command = listOf(File(bin, "git").absolutePath) + args
        val builder = ProcessBuilder(command).directory(cwd).redirectErrorStream(true)
        builder.environment().apply {
            put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
            put("PATH", "${bin.absolutePath}:/system/bin")
            put("GIT_EXEC_PATH", bin.absolutePath)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_ATTR_NOSYSTEM", "1")
            put("HOME", project.parentFile!!.absolutePath)
            put("GIT_AUTHOR_NAME", "OpenCode")
            put("GIT_AUTHOR_EMAIL", "opencode@localhost")
            put("GIT_COMMITTER_NAME", "OpenCode")
            put("GIT_COMMITTER_EMAIL", "opencode@localhost")
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return process.waitFor() to output
    }

    @Test
    fun gitRunsFromTheBundledBinary() {
        if (!packaged) return
        val (code, output) = git("--version")
        assertEquals("git --version failed: $output", 0, code)
        assertTrue("unexpected output: $output", output.startsWith("git version"))
    }

    @Test
    fun aFullLocalWorkflowWorks() {
        if (!packaged) return

        assertEquals(0, git("init", "-q", "-b", "main").first)

        File(project, "README.md").writeText("hello\n")
        assertEquals(0, git("add", "README.md").first)

        val (commitCode, commitOutput) = git("commit", "-q", "-m", "first commit")
        assertEquals("commit failed: $commitOutput", 0, commitCode)

        val (statusCode, status) = git("status", "--short", "--branch")
        assertEquals(0, statusCode)
        assertTrue("expected the branch in: $status", status.contains("main"))

        val (_, log) = git("log", "--oneline")
        assertTrue("expected the commit in: $log", log.contains("first commit"))

        // A change the agent might make, and the diff a user would inspect.
        File(project, "README.md").appendText("world\n")
        val (diffCode, diff) = git("diff", "--stat")
        assertEquals(0, diffCode)
        assertTrue("expected README in: $diff", diff.contains("README.md"))

        val (_, branches) = git("branch")
        assertTrue("expected main in: $branches", branches.contains("main"))
    }

    @Test
    fun gitFindsItsRemoteHelperByName() {
        if (!packaged) return
        // Not a network test. `git remote add` then asking for the URL proves the
        // repository plumbing works; whether the helper binary is reachable is
        // what the symlink exists for, and is checked by its presence.
        git("init", "-q", "-b", "main")
        assertEquals(0, git("remote", "add", "origin", "https://example.invalid/repo.git").first)

        val (code, url) = git("remote", "get-url", "origin")
        assertEquals(0, code)
        assertEquals("https://example.invalid/repo.git", url)

        assertTrue(
            "git-remote-https should be linked for https remotes to work",
            File(bin, "git-remote-https").exists(),
        )
    }

    @Test
    fun theProjectPathIsRealAndInsideTheSandbox() {
        // What makes any of this work: a POSIX path the runtime can chdir into,
        // not a content:// URI.
        assertTrue(project.isAbsolute)
        assertTrue(project.canonicalPath.startsWith(context.filesDir.canonicalPath))
    }
}
