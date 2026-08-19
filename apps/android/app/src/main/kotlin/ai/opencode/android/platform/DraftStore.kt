package ai.opencode.android.platform

import android.content.Context
import android.util.Base64
import ai.opencode.android.security.KeystoreCipher
import ai.opencode.android.security.ValueCipher
import java.io.File
import java.security.MessageDigest

/**
 * Prompt drafts and their attached blobs.
 *
 * Drafts are what the user has typed but not sent. They must survive process
 * death - on a phone the app is killed routinely while backgrounded, and losing
 * a half-written prompt because the user took a phone call is exactly the kind
 * of thing that makes an app feel untrustworthy (.claude/rules/android.md N7).
 *
 * Text lives in preferences and is encrypted with everything else there. Blobs
 * are content-addressed files on disk, so the same pasted image referenced from
 * several drafts is stored once.
 *
 * Blob *files* are not encrypted. They are app-private and the app sets
 * `allowBackup="false"`, so reading them needs root or a physical extraction;
 * encrypting them is a separate question about large binary payloads, deferred
 * to M10 with the rest of the storage hardening. Draft *text* - the part that
 * routinely contains code and pasted secrets - is encrypted.
 */
class DraftStore(context: Context, cipher: ValueCipher = KeystoreCipher()) {

    private val prefs = PreferenceStore(context, cipher)
    private val blobDir = File(context.filesDir, "drafts/blobs").apply { mkdirs() }

    fun get(key: String): String? = prefs.get(STORE, key)

    fun set(key: String, value: String) = prefs.set(STORE, key, value)

    fun remove(key: String) = prefs.remove(STORE, key)

    /** Stores a blob and returns its content id. Identical bytes reuse one file. */
    fun putBlob(base64: String, type: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val id = sha256(bytes)
        val file = File(blobDir, id)
        if (!file.exists()) file.writeBytes(bytes)
        if (type.isNotEmpty()) prefs.set(TYPES, id, type)
        return id
    }

    /** Returns `{base64, type}` for a stored blob, or null when it is gone. */
    fun getBlob(id: String): Pair<String, String>? {
        // The id is content-addressed hex from putBlob; anything else is refused
        // rather than joined onto a path.
        if (!id.matches(Regex("[0-9a-f]{64}"))) return null
        val file = File(blobDir, id)
        if (!file.exists()) return null
        val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return base64 to (prefs.get(TYPES, id) ?: "application/octet-stream")
    }

    private companion object {
        const val STORE = "drafts"
        const val TYPES = "draft-blob-types"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
