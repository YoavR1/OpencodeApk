package ai.opencode.android.platform

import android.content.Context
import androidx.core.content.edit

/**
 * Named key/value stores backing `Platform.storage`.
 *
 * The shared UI asks for stores by name ("default.dat", "opencode.global.dat",
 * per-window files on desktop), so each name maps to its own SharedPreferences
 * file. Values are opaque strings the UI serialises itself.
 *
 * Deliberately NOT encrypted. Nothing stored through this path is a secret
 * today - it is UI state, layout preferences and the selected server URL.
 * Provider credentials get Keystore-backed storage of their own in M10, and
 * putting them here later would be the mistake; see .claude/rules/android.md N8.
 */
class PreferenceStore(private val context: Context) {

    private fun prefs(name: String) = context.getSharedPreferences(fileName(name), Context.MODE_PRIVATE)

    fun get(name: String, key: String): String? = prefs(name).getString(key, null)

    fun set(name: String, key: String, value: String) = prefs(name).edit { putString(key, value) }

    fun remove(name: String, key: String) = prefs(name).edit { remove(key) }

    fun clear(name: String) = prefs(name).edit { clear() }

    fun keys(name: String): List<String> = prefs(name).all.keys.sorted()

    private companion object {
        /**
         * Store names come from web content, so they are constrained to a safe
         * filename rather than trusted. An unconstrained name could otherwise
         * reach outside the preferences directory.
         */
        fun fileName(name: String): String {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            return if (safe.isEmpty()) "default" else "oc_$safe"
        }
    }
}
