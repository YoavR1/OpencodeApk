package ai.opencode.android.platform

import android.content.Context
import androidx.core.content.edit
import ai.opencode.android.security.KeystoreCipher
import ai.opencode.android.security.ValueCipher

/**
 * Named key/value stores backing `Platform.storage`.
 *
 * The shared UI asks for stores by name ("default.dat", "opencode.global.dat",
 * per-window files on desktop), so each name maps to its own SharedPreferences
 * file. Values are opaque strings the UI serialises itself.
 *
 * **Values are encrypted** with a Keystore-held key (see [ValueCipher]). M4 left
 * this store in the clear on the grounds that it held only UI state; M5 changed
 * that premise. Upstream persists a whole `ServerConnection.Http` - including
 * `http.password` - into its `server.v3` store, and that store is this one.
 *
 * Everything is encrypted rather than just the values believed to be secret,
 * because deciding which is which is a judgement that has to be re-made every
 * time upstream persists something new, and it fails silently when it is made
 * wrongly. Drafts are covered too, which is a feature: an unsent prompt is
 * often the most sensitive thing the app is holding. See ADR-0017.
 *
 * Key *names* stay in the clear. They are structural ("server.v3",
 * "settings.v3"), the values carry the content, and leaving them readable keeps
 * [keys] meaningful.
 */
class PreferenceStore(
    private val context: Context,
    private val cipher: ValueCipher = KeystoreCipher(),
) {

    private fun prefs(name: String) = context.getSharedPreferences(fileName(name), Context.MODE_PRIVATE)

    /** Returns null for a value that cannot be decrypted; see [ValueCipher.decrypt]. */
    fun get(name: String, key: String): String? =
        prefs(name).getString(key, null)?.let { cipher.decrypt(it) }

    fun set(name: String, key: String, value: String) = prefs(name).edit { putString(key, cipher.encrypt(value)) }

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
