package ai.opencode.android.security

/**
 * Encrypts the values this app stores on disk.
 *
 * An interface rather than a concrete class so the storage layer can be tested
 * without a hardware-backed keystore, and so M10 can introduce a second scheme
 * (e.g. one requiring user authentication) without touching every call site.
 */
interface ValueCipher {

    /** Returns an opaque, self-describing string safe to persist. */
    fun encrypt(plaintext: String): String

    /**
     * Reverses [encrypt].
     *
     * Returns null when the value cannot be read - a rotated or invalidated key,
     * a truncated file, a value written by a scheme this build does not know.
     * That is a normal outcome, not an error: the caller treats it as "no value
     * stored" and the user re-enters whatever it was. Throwing here would turn a
     * lost preference into a crash loop on launch.
     */
    fun decrypt(encoded: String): String?
}
