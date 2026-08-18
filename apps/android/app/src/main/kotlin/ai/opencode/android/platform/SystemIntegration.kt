package ai.opencode.android.platform

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import ai.opencode.android.util.SafeLog
import java.io.ByteArrayOutputStream

/**
 * Clipboard, sharing and external links.
 *
 * Each entry point re-validates its input. These are reachable from web content
 * through the bridge, so none of them may trust the caller - the renderer's own
 * checks are a convenience, not a control.
 */
class SystemIntegration(private val context: Context) {

    fun readText(): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    fun writeText(text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode", text))
        return true
    }

    /**
     * Reads an image off the clipboard as `{base64, type}`.
     *
     * Re-encoded as PNG rather than passed through: the clipboard may hold any
     * decodable format, and the renderer expects one it can put in a data URL.
     */
    fun readImage(): Pair<String, String>? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null

        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/png"
    }

    /** Opens the system share sheet. Requires an Activity context to be visible. */
    fun share(activity: Activity, text: String, title: String?): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!title.isNullOrEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return try {
            activity.startActivity(Intent.createChooser(send, title))
            true
        } catch (error: ActivityNotFoundException) {
            SafeLog.w("no activity to handle share", error)
            false
        }
    }

    fun openExternal(url: String): Boolean {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) {
            SafeLog.w("refused openExternal for scheme=${uri.scheme}")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            SafeLog.w("no activity to handle openExternal", error)
            false
        }
    }

    companion object {
        /** Only these may cross to the system. Everything else is an intent-injection risk. */
        val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
    }
}
