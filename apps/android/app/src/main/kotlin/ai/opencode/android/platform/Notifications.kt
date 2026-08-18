package ai.opencode.android.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.util.SafeLog

/**
 * `Platform.notify`, which was DEGRADED through M3 because Android WebView has
 * no Notification API and this needs a channel, a permission and a click route.
 *
 * Tapping a notification brings the Activity forward and relays the tag back to
 * the renderer, so the UI can act on which notification was tapped.
 */
class Notifications(private val context: Context) {

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_sessions),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_sessions_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun permitted(): Boolean =
        // POST_NOTIFICATIONS only exists from API 33; below that, posting is allowed.
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns false when the notification could not be posted, rather than pretending. */
    fun post(title: String, body: String, tag: String): Boolean {
        if (!permitted()) {
            SafeLog.d("notification suppressed: POST_NOTIFICATIONS not granted")
            return false
        }
        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_TAG, tag)
        }
        val pending = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        // POST_NOTIFICATIONS can be revoked between permitted() above and this
        // call, so the SecurityException is caught by name rather than assumed
        // away. Lint's MissingPermission check also cannot follow the permitted()
        // helper, and an explicit catch is what it asks for.
        return try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            SafeLog.w("not permitted to post notification", e)
            false
        } catch (e: RuntimeException) {
            SafeLog.w("failed to post notification", e)
            false
        }
    }

    companion object {
        const val CHANNEL_ID = "opencode.sessions"
        const val EXTRA_TAG = "opencode.notification.tag"
        private const val NOTIFICATION_ID = 1
    }
}
