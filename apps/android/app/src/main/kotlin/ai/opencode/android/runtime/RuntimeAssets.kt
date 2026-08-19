package ai.opencode.android.runtime

import android.content.Context
import android.os.Build
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
 * 37 MB copy on each start would be a visible delay for no reason.
 *
 * **What counts as "the APK changed" is a security property**, not an
 * optimisation detail. The extracted copy is the JavaScript the server actually
 * runs, so a marker that fails to notice a new APK means a fix shipped in the
 * bundle silently never takes effect - the app keeps executing the old code and
 * reports the new version number. That is exactly the shape of a patch that
 * looks applied and is not.
 *
 * The marker therefore includes `lastUpdateTime` and the version *code* from the
 * installed package, both of which the platform changes on every install. An
 * earlier version digested the asset listing and the version *name*: file
 * contents are not in a listing, and the version name is a constant during
 * development, so editing a bundled file and reinstalling left the old copy in
 * place. Found in M10 by checking the device against the APK - the launcher on
 * disk was two milestones old.
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

    /**
     * Changes whenever a different APK is installed, which is what forces a copy.
     *
     * `lastUpdateTime` is the load-bearing part: it is set by the package manager
     * on every install and upgrade, including a debug reinstall of an identical
     * version. The version code and the asset listing are cheap to add and make
     * the marker readable as "which build is extracted here".
     *
     * Digesting the asset *contents* would be the most direct statement of the
     * property, but it means reading 37 MB before the server can start, on every
     * launch, to answer a question the package manager has already answered.
     */
    private fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity().toByteArray())
        fun walk(path: String) {
            val children = context.assets.list(path).orEmpty().sorted()
            digest.update(path.toByteArray())
            for (child in children) walk("$path/$child")
        }
        walk(ASSET_ROOT)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Identifies the installed package: version name, version code, install time.
     *
     * If the package cannot be read the value falls back to something *unique*
     * rather than something stable, so the failure mode is an unnecessary copy
     * rather than a silently stale runtime.
     */
    private fun identity(): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "$versionName|$code|${info.lastUpdateTime}"
    }.getOrElse {
        SafeLog.w("could not read the package info; re-extracting the runtime", it)
        "$versionName|unknown|${System.nanoTime()}"
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
