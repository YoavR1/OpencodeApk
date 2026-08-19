package ai.opencode.android

import android.app.Application
import ai.opencode.android.runtime.LocalRuntimeController
import ai.opencode.android.runtime.RuntimeService
import ai.opencode.android.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process entry point, and the owner of the runtime.
 *
 * **The runtime belongs to the process, not to the Activity.** Android destroys
 * and recreates an Activity for reasons the user never sees - a locale change, a
 * theme change, "don't keep activities" - and an Activity-owned runtime means a
 * new controller each time, with a **new password** that no longer matches the
 * server already running, and an old runtime nobody is watching any more.
 *
 * Measured on a device (M9): a recreation did not in fact leave a second server
 * running, because the renderer's handle survived and nothing asked again. That
 * is luck rather than design - the invariant should not depend on which of two
 * things happens first - so ownership moved here, where the lifetime actually
 * matches: one runtime per process, from first use until the process ends.
 *
 * Note this class runs once **per process**. If the server is ever moved into its
 * own `android:process`, this must not start a runtime there.
 */
class OpenCodeApplication : Application() {

    /**
     * Lives as long as the process. Not tied to any Activity, so a recreation
     * does not cancel the runtime's monitoring along with the UI's coroutines.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Constructed here, but nothing is started until something calls
     * `awaitReady()`. Building the controller is just objects; the server
     * process appears only when the UI asks for an address.
     */
    val runtime: LocalRuntimeController by lazy {
        LocalRuntimeController(
            context = this,
            scope = scope,
            versionName = BuildConfig.VERSION_NAME,
        )
    }

    override fun onCreate() {
        super.onCreate()
        SafeLog.i("OpenCode ${BuildConfig.VERSION_NAME} starting (debug=${BuildConfig.DEBUG})")

        // Keep the process alive exactly while the agent is working, and not a
        // moment longer. The signal comes from the server rather than the UI -
        // the server is the authority on whether a turn is running, and it needs
        // no coupling to upstream's internals to ask.
        scope.launch {
            runtime.busy.collect { busy ->
                if (busy) RuntimeService.start(this@OpenCodeApplication)
                else RuntimeService.stop(this@OpenCodeApplication)
            }
        }
    }
}
