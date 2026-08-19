package ai.opencode.android.runtime

import ai.opencode.android.util.SafeLog
import java.io.File
import org.json.JSONObject

/**
 * Makes the bundled tools findable under the names they expect.
 *
 * Everything executable ships as `lib*.so`, because that is the only form Android
 * extracts and the only place W^X permits execution from. But `git` looks for
 * `git-remote-https` by exact name, and a shell command asks for `git`, not
 * `libgit.so`.
 *
 * The bridge is a directory of symlinks in app-private storage pointing back into
 * `nativeLibraryDir`. Executing through such a symlink is permitted - the kernel
 * checks the target, which lives in an exec-permitted directory - verified on a
 * device before this was built on.
 *
 * The link table comes from `tools.json`, written by the packaging script, so
 * adding a tool is one edit there rather than two in different languages.
 */
class RuntimeTools(private val nativeLibraryDir: File, private val runtimeRoot: File) {

    /** The directory to put on PATH, or null when no tools are packaged. */
    fun install(): File? {
        val manifest = File(runtimeRoot, MANIFEST)
        if (!manifest.exists()) {
            SafeLog.d("no tools manifest; this build ships no bundled tools")
            return null
        }

        val links = runCatching {
            val json = JSONObject(manifest.readText()).getJSONObject("links")
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrElse {
            SafeLog.w("could not read the tools manifest", it)
            return null
        }

        val bin = File(runtimeRoot, BIN).apply { mkdirs() }
        var linked = 0
        for ((name, library) in links) {
            val target = File(nativeLibraryDir, library)
            if (!target.exists()) {
                // A build may package some tools and not others; say which is
                // missing rather than failing the whole runtime for it.
                SafeLog.d("tool $name unavailable: $library is not in this build")
                continue
            }
            val link = File(bin, name)
            runCatching {
                if (link.exists() || isSymlink(link)) link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
                linked++
            }.onFailure { SafeLog.w("could not link $name", it) }
        }

        SafeLog.d("linked $linked tool(s) into ${bin.name}")
        return if (linked > 0) bin else null
    }

    /**
     * Where git looks for its helpers.
     *
     * The same directory as the tools: git resolves `git-remote-https` relative
     * to `GIT_EXEC_PATH`, and that is where the symlink is.
     */
    fun gitExecPath(): File = File(runtimeRoot, BIN)

    /** `exists()` follows symlinks, so a dangling one needs asking differently. */
    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    companion object {
        const val MANIFEST = "tools.json"
        const val BIN = "bin"
    }
}
