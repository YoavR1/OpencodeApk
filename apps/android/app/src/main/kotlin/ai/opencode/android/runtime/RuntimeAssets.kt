package ai.opencode.android.runtime

import android.content.Context
import ai.opencode.android.util.SafeLog
import java.io.File
import java.security.MessageDigest

/**
 * Unpacks the server bundle from assets into app-private storage.
 *
 * The bundle is *data* - JavaScript and WASM that Node reads - so it may live in
 * a writable directory. Only the runtime binary is subject to W^X, and that ships
 * as a `lib*.so` in `nativeLibraryDir` instead (M6).
 *
 * Assets are re-extracted when the APK changes rather than on every launch: a
 * 37 MB copy on each start would be a visible delay for no reason. The marker is
 * a digest of the asset listing plus the version name, which changes whenever a
 * new build is installed.
 */
class RuntimeAssets(private val context: Context, private val versionName: String) {

    val root: File get() = File(context.filesDir, "runtime")

    private val marker: File get() = File(root, ".installed")

    /** Extracts if needed and returns the directory the launcher lives in. */
    fun install(): File {
        val expected = fingerprint()
        if (marker.exists() && marker.readText() == expected) {
            SafeLog.d("runtime assets already installed")
            return root
        }

        SafeLog.d("installing runtime assets")
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()
        copyAsset(ASSET_ROOT, root)
        marker.writeText(expected)
        return root
    }

    /** Changes whenever the packaged assets change, which is what forces a re-copy. */
    private fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(versionName.toByteArray())
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty().sorted()
            digest.update(path.toByteArray())
            for (child in children) walk("$path/$child")
        }
        walk(ASSET_ROOT)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun copyAsset(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        for (child in children) copyAsset("$path/$child", File(destination, child))
    }

    companion object {
        const val ASSET_ROOT = "runtime"
    }
}
