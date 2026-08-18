package ai.opencode.android.platform

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.opencode.android.util.SafeLog
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Project folder selection through the Storage Access Framework.
 *
 * SAF, not a storage permission. The app never asks for broad filesystem access
 * (.claude/rules/android.md N4): the user picks a folder, Android grants access
 * to exactly that subtree, and the grant is persisted so it survives reinstall
 * of the Activity and process death.
 *
 * Registered at Activity construction because `registerForActivityResult` must
 * be called before the Activity is STARTED.
 */
class DirectoryPicker(activity: AppCompatActivity) {

    private val contentResolver = activity.contentResolver
    private var pending: ((Uri?) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Uri?> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val callback = pending
            pending = null
            if (uri != null) persist(uri)
            callback?.invoke(uri)
        }

    /** Returns the picked tree URI as a string, or null when the user cancelled. */
    suspend fun pick(): String? = suspendCoroutine { continuation ->
        if (pending != null) {
            // A second picker while one is open would strand the first callback.
            continuation.resume(null)
            return@suspendCoroutine
        }
        pending = { uri -> continuation.resume(uri?.toString()) }
        runCatching { launcher.launch(null) }.onFailure {
            SafeLog.w("could not open the directory picker", it)
            pending = null
            continuation.resume(null)
        }
    }

    /**
     * Takes a persistable grant so the folder is still readable after a restart.
     * Without this the URI works only until the process dies.
     */
    private fun persist(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { SafeLog.w("could not persist the directory grant", it) }
    }
}
