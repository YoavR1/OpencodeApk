package ai.opencode.android.runtime

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.util.SafeLog

/**
 * Keeps the process alive while the agent is actually working.
 *
 * Android offers no other way to guarantee that background work finishes: a
 * backgrounded app is a cached process and may be killed at any moment under
 * memory pressure. A turn that takes two minutes while the user reads something
 * else would otherwise be lost halfway through, and the user would have no idea
 * why (.claude/rules/android.md N7).
 *
 * **It runs only while there is a turn in flight.** A foreground service held
 * open for the life of the app would sit in the notification shade doing nothing
 * and cost battery for it, which N9 rules out. The renderer knows when the agent
 * is busy and says so; this starts and stops on that signal.
 */
class RuntimeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SafeLog.d("runtime service stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> start()
        }
        // Deliberately not sticky: if Android kills the process mid-turn, the
        // turn is already lost, and silently restarting a service with no UI and
        // no work to do would just burn battery.
        return START_NOT_STICKY
    }

    private fun start() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.runtime_service_title))
            .setContentText(getString(R.string.runtime_service_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            SafeLog.d("runtime service started")
        }.onFailure {
            // From Android 12 a foreground service cannot always be started from
            // the background. Losing the guarantee is bad; crashing the app over
            // it is worse, and the turn may well finish anyway.
            SafeLog.w("could not start the runtime service", it)
            stopSelf()
        }
    }

    companion object {
        const val CHANNEL_ID = "opencode.runtime"
        private const val ACTION_STOP = "ai.opencode.android.runtime.STOP"
        private const val NOTIFICATION_ID = 2

        /** Creates the channel the service notification uses. Safe to call repeatedly. */
        fun ensureChannel(context: Context) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.runtime_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.runtime_channel_description)
                setShowBadge(false)
            }
            androidx.core.app.NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Called when a turn starts. Idempotent. */
        fun start(context: Context) {
            ensureChannel(context)
            runCatching { context.startForegroundService(Intent(context, RuntimeService::class.java)) }
                .onFailure { SafeLog.w("could not request the runtime service", it) }
        }

        /** Called when the agent goes idle. Idempotent. */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RuntimeService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure { SafeLog.d("runtime service was not running") }
        }
    }
}
