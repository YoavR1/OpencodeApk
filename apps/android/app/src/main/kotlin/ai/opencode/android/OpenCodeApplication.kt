package ai.opencode.android

import android.app.Application
import ai.opencode.android.util.SafeLog

/**
 * Process entry point.
 *
 * From M7 this class also has to cope with running in more than one process: the
 * OpenCode server is expected to live in its own Android process, and
 * `onCreate` runs once per process. Keep anything added here cheap and
 * process-safe.
 */
class OpenCodeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        SafeLog.i("OpenCode ${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")
    }
}
